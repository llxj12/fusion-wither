/*
 * 【文件职责】游戏总线（MinecraftForge.EVENT_BUS）的事件订阅处：凋灵加入世界时，把最大血量校准到配置值、
 *   清理历史遗留的护甲修饰符、锁定击退抗性，并补满出生血量。
 * 【所属模块】模组入口（com.fusionwither 包根），与 ModSetup 构成「按总线拆类」的两半。
 * 【协作关系】@Mod.EventBusSubscriber(bus = FORGE) 由 Forge 自动注册；本类只被 Forge 调用，
 *   自身不引用任何战斗逻辑类，数值来源为 wither.WitherConfig.WITHER_MAX_HEALTH。
 * 【关键约束】两条总线的事件不能挂在同一个订阅类上（否则启动崩溃，见类注释）；
 *   属性数值只能通过「对单个实体的修饰符」施加，禁止改基值 setBaseValue 以外泄到其他凋灵 ——
 *   代码里唯一的 setBaseValue 只用于击退抗性，且仅在原值 < 1.0 时执行。
 */
package com.fusionwither;

import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 游戏总线（{@code MinecraftForge.EVENT_BUS}）的事件订阅处，负责「凋灵被放进世界」这一时刻的属性校准。
 *
 * <p><b>为什么必须与 {@link ModSetup} 分开 —— 实测崩溃后的修正：</b>早先 {@code onEntityJoin} 与
 * {@code onModifyEntityAttributes} 放在同一个类里，而那个类声明的是 {@code bus = MOD}，启动时崩溃：
 * <pre>
 * java.lang.IllegalArgumentException:
 * Method com.fusionwither.ModSetup.onEntityJoin(EntityJoinLevelEvent) has @SubscribeEvent
 * annotation, but takes an argument that is not a subtype of the base type interface
 * net.minecraftforge.fml.event.IModBusEvent
 * </pre>
 * 原因是 {@link EntityJoinLevelEvent} 属于游戏总线事件，而 {@code EntityAttributeModificationEvent}
 * 属于模组总线事件，两者不能挂在同一个 {@code @Mod.EventBusSubscriber} 上。因此按总线拆成两个类：
 * <ul>
 *   <li>{@link ModSetup}　　　　 → MOD 总线（模组生命周期内的事件）</li>
 *   <li>{@code GameBusEvents}　→ GAME 总线（本类）</li>
 * </ul>
 *
 * <p><b>设计要点 —— 为什么走「实体修饰符 + 实测校准」而不是直接设基值：</b>
 * 基值是属性实例上的静态共享数据，改了会影响所有凋灵甚至其他模组读到的情况；
 * 修饰符随实体走，能精确到「这一只」。而修饰符的乘法语义在本环境实测与文档推演不一致，
 * 所以血量不是照公式一次算完，而是「施加 → 观测 → 修正」的闭环校准（见 {@link #applyHealthModifierToTarget}）。
 *
 * <p><b>生命周期：</b>本类无实例、无状态，每只凋灵加入世界时被回调一次；配置常量在类加载期读入。
 */
@Mod.EventBusSubscriber(modid = NewMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GameBusEvents {

    private GameBusEvents() {}

    /**
     * 血量修饰符的固定 UUID。
     * <p><b>为什么必须是固定常量而不是随机生成：</b>修饰符按 UUID 去重，固定值保证同一只凋灵
     * 反复进出世界（区块重载）时不会把同一个修饰符叠加两次，也保证后来能用同一个 ID 精确移除。
     */
    private static final UUID MAX_HEALTH_MODIFIER_ID =
            UUID.fromString("6b1a1f8e-9c2d-4a7b-8e3f-1d2c3b4a5e6f");

    /** 护甲修饰符的固定 UUID。当前只用于「清理旧版本残留」，不会被新增。 */
    private static final UUID ARMOR_MODIFIER_ID =
            UUID.fromString("7c2b2f9f-0d3e-4b8c-9f40-2e3d4c5b6a70");

    /**
     * 凋灵加入世界时，给它挂上血量修饰符、清理历史残留，并补满出生血量。
     *
     * <p><b>调用时机：</b>实体被加入世界时由游戏总线回调，服务端与客户端都会触发，因此先按
     * {@code isClientSide()} 过滤 —— 客户端实体只是服务端状态的镜像，在其上加修饰符没有意义
     * 而且会污染本地镜像状态。
     *
     * <p><b>副作用：</b>修改该凋灵的最大生命属性（永久修饰符）、可能移除护甲修饰符、
     * 可能把击退抗性基值设为 1.0、可能把当前血量补满；全程只打日志，不发任何封包。
     *
     * <p><b>为什么用「永久修饰符」而不是 {@code setBaseValue}：</b>基值是共享的静态数据，
     * 改了会影响所有凋灵（包括其他模组读到的），修饰符是每只实体独立的。
     *
     * @param event 实体加入世界事件，本方法只处理其中的服务端 {@code WitherBoss}
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof WitherBoss wither)) {
            return;
        }

        float healthBefore = wither.getHealth();
        float maxBefore = wither.getMaxHealth();

        // 最大血量不使用「假设基线是 300 的加法修饰符」：实机日志显示凋灵出生时上限其实是 320
        // （与从字节码读到的 300 不符），硬编码 +300 会让差距被放大成 600 对不上的数量级，
        // 进而使阶段阈值、回血上限全部算错。因此改为按配置目标值做乘法修饰符 + 实测校准。
        //
        // MULTIPLY_TOTAL 的文档语义：总值 = 基础 × (1 + amount)，即 amount 是「增量倍率」
        // （想 320 → 600 应为 amount = 600/320 - 1 = 0.875）。
        // 但实测这条路径给出的是 37.5，说明本环境下它还与其他修饰符串成乘法链、或求值顺序不同。
        // 结论：不再对未知求值规则做假设，改成「先算候选值 → 读回实际上限 → 按实际比例修正」。
        double curMax = wither.getMaxHealth();
        double targetMax = WitherConfig.WITHER_MAX_HEALTH;
        NewMod.LOGGER.info("[凋灵/诊断] 凋灵加入世界：血={} 上限={} 目标上限={}",
                healthBefore, curMax, targetMax);
        applyHealthModifierToTarget(wither, targetMax);

        // 这里曾应用 +20 护甲修饰符，已移除：原版护甲 4 + 20 = 24，而 MC 1.20.1 每点护甲约 4% 减伤、
        // 上限 80%，于是下界合金剑 8 伤害实际只掉 1.6，血条 600 要砍近四百次 ——
        // 用户反馈「近战无法造成伤害」（实为每次只掉一点点、肉眼看不出来）。
        // 二阶段的防护改由「弹射物免疫」（护盾类机制）表达，不再叠加数值减伤。
        var armor = wither.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifiers().stream()
                .anyMatch(m -> m.getId().equals(ARMOR_MODIFIER_ID))) {
            // 清掉历史上已加过的护甲修饰符（旧存档里已有的凋灵仍带着它）
            armor.removeModifier(ARMOR_MODIFIER_ID);
        }
        // 击退抗性：原版凋灵为 1.0（完全免疫击退）。取消原版 AI 后若击退抗性不足，
        // 凋灵会被玩家打得四处乱飞，因此生成时确保它是 1.0。
        var kb = wither.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null && kb.getBaseValue() < 1.0D) {
            kb.setBaseValue(1.0D);
        }

        // 加完最大血量后必须补血，否则凋灵以半血出生：
        // 原版凋灵构造时血 = 300、上限 = 300；本方法把上限修饰到 600 而当前血量仍是 300，
        // 比例 50%，恰好等于二阶段阈值（<= 0.50），于是凋灵一被放出来就直接进二阶段 ——
        // 血条显示一半、立刻触发入场大爆炸与坠落、30 tick 的入场演出被跳过。
        //
        // 修法：只有当「血量没被改过（healthBefore 仍 <= 记录到的旧上限）且低于新上限」时才补满，
        // 这样不会把玩家已经打掉的血重新灌满（例如区块重载后再进世界）。
        if (healthBefore > 0.0F && healthBefore <= maxBefore
                && wither.getHealth() < wither.getMaxHealth()) {
            wither.setHealth(wither.getMaxHealth());
            NewMod.LOGGER.info("[凋灵] 凋灵 {} 出生血量已补满：{} -> {} / {}",
                    wither.getUUID(), healthBefore, wither.getHealth(), wither.getMaxHealth());
        }
    }

    /**
     * 把凋灵的最大血量校准到目标值 —— 先试算、再实测、按实测修正的闭环。
     *
     * <p><b>为什么不直接套乘法公式：</b>本环境的 {@code MULTIPLY_TOTAL} 实测行为与文档推演不符
     * （按公式算出倍率 0.875，实测上限却得到 37.5），说明还有别的修饰符参与乘法链、或求值顺序与预期不同。
     * 因此这里不做任何关于乘法语义的假设，只做「施加 → 读回实际情况 → 按 目标 / 实际 修正倍率」的迭代。
     *
     * <p><b>调用时机：</b>仅在 {@link #onEntityJoin} 中、且实体为服务端凋灵时调用一次。
     *
     * <p><b>副作用：</b>反复增删同一个 UUID 的修饰符；每次尝试会向 {@code getMaxHealth()} 求值并打日志。
     * 属性取不到时直接放弃（只打 warn），不抛异常以免影响实体加入世界。
     *
     * <p><b>为什么最多 3 次：</b>每次尝试都是「观测后按比例修正」，正常一两轮即收敛；
     * 设上限是为了避免某次观测异常（如上限被压到 0）时陷入死循环，收敛失败保留 warn 供排查。
     *
     * @param wither    待校准的服务端凋灵实体
     * @param targetMax 目标最大生命值（来自 {@code WitherConfig.WITHER_MAX_HEALTH}，正值）
     */
    private static void applyHealthModifierToTarget(LivingEntity wither, double targetMax) {
        var instance = wither.getAttribute(Attributes.MAX_HEALTH);
        if (instance == null) {
            NewMod.LOGGER.warn("[凋灵/诊断] 取不到 MAX_HEALTH 属性 —— 血量修饰符无法应用");
            return;
        }

        // 先清掉历史残留（可能是旧版本的加法修饰符，会污染本次校准的观测值）
        instance.removeModifier(MAX_HEALTH_MODIFIER_ID);

        double amount = targetMax / Math.max(1.0E-6D, wither.getMaxHealth()) - 1.0D;
        for (int attempt = 1; attempt <= 3; attempt++) {
            instance.removeModifier(MAX_HEALTH_MODIFIER_ID);
            instance.addPermanentModifier(new AttributeModifier(
                    MAX_HEALTH_MODIFIER_ID, "fusion_wither_health",
                    amount, AttributeModifier.Operation.MULTIPLY_TOTAL));

            double actual = wither.getMaxHealth();
            NewMod.LOGGER.info("[凋灵/诊断] 第 {} 次尝试：倍率={} → 实际上限={}（目标 {}）",
                    attempt, amount, actual, targetMax);

            // 容差 1.0：血量是 float，且原版本身可能有取整误差，允许 1 点内即视为收敛
            if (Math.abs(actual - targetMax) <= 1.0D) {
                NewMod.LOGGER.info("[凋灵/诊断] 血量校准成功：上限={}", actual);
                return;
            }
            if (actual <= 0.0D) {
                break;
            }
            // 按实际值修正倍率：把「目标相对当前实际值」的比例折算回新的 amount
            double factor = targetMax / actual;
            amount = (1.0D + amount) * factor - 1.0D;
        }
        NewMod.LOGGER.warn("[凋灵/诊断] 血量校准未在 3 次内收敛，当前上限={}（目标 {}）",
                wither.getMaxHealth(), targetMax);
    }
}

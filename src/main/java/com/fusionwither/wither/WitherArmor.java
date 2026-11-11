/*
 * 【文件职责】维护凋灵"护甲（只免疫弹射物）"的开关状态，并负责每帧喂 Boss 血条的
 *             进度与颜色。
 * 【所属模块】wither 核心（凋灵辅助能力）。
 * 【协作关系】护甲状态被 ProjectileImpactEvent 的处理器读取（命中凋灵的弹射物在那里被
 *             取消，本类不做判定）；状态由二阶段逻辑每帧 markArmored，命令 /wboss armor
 *             on|off 走 restoreArmor/clearArmor；血条由 WitherBrain 通过
 *             WitherBossAccessor 拿到后交给 updateBossBar 刷新。
 * 【关键约束】"关闭护甲"必须同时抑制后续自动标记，否则二阶段下一帧就会把护甲打开，
 *             命令等于无效。
 */
package com.fusionwither.wither;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.boss.wither.WitherBoss;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 凋灵的护甲状态与血条刷新。
 *
 * <p><b>规格的关键约束：护甲只免疫弹射物</b>；爆炸伤害与近战仍然有效。
 *
 * <p><b>实现方式：</b>不碰实体属性、不碰 {@code hurt}，而是在 {@code ProjectileImpactEvent}
 * 里判断"命中凋灵的弹射物"并取消本次命中；<b>不限制弹射物类型</b> —— 弓、弩、三叉戟、
 * 雪球、火球等一律挡下。之所以能保证爆炸与近战不被误挡，是因为它们根本不经过这个事件
 * （近战走 {@code mobAttack} 伤害源，爆炸走 {@code Level.explode}）。
 *
 * <p><b>与同包其他类的关系：</b>本类只是状态的持有者，真正的命中判定在外部的
 * {@code ProjectileImpactEvent} 处理器里通过 {@link #hasArmor} 查询；血条的进度/颜色
 * 也由本类统一写入，调用方只提供凋灵与阶段信息。
 *
 * <p><b>不变量：</b>被 {@link #clearArmor} 抑制过的凋灵，在 {@link #restoreArmor} 之前
 * 无论谁调用 {@link #markArmored} 都不会重新获得护甲；抑制判断刻意写在 markArmored 内部，
 * 因此所有现存与将来新增的标记点都自动受控。
 *
 * <p><b>生命周期：</b>纯静态状态表，无实例；条目在凋灵死亡时由 {@link #forget} 移除。
 */
public final class WitherArmor {

    private WitherArmor() {}

    /** 当前处于「护甲生效」状态的凋灵集合；弱引用，实体卸载后条目自动消失。 */
    private static final Set<WitherBoss> ARMORED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** 被显式关闭护甲的凋灵（调试用）。
     *  <p>存在理由：二阶段里 {@code PhaseTwoLogic.tick()} 每帧都会调用
     *  {@link #markArmored}。若只调用 {@link #clearArmor} 而不加抑制，
     *  下一帧护甲就会被重新标记，命令等于无效。</p>*/
    private static final Set<WitherBoss> SUPPRESSED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 标记该凋灵的护甲生效。
     *
     * <p><b>调用时机：</b>二阶段逻辑每帧调用（以及阶段切换时的入场标记）。
     *
     * <p><b>副作用：</b>把凋灵加入生效集合，此后弹射物命中会被挡下。
     *
     * <p><b>为什么抑制判断放在本方法内部而不是各个调用点上：</b>这样所有现存与将来新增的
     * 标记点都会自动受 {@link #clearArmor} 的抑制标志控制，避免重演"改了一处、漏了另一处"
     * 那类不一致问题。
     *
     * @param wither 目标凋灵
     */
    public static void markArmored(WitherBoss wither) {
        if (SUPPRESSED.contains(wither)) {
            return;
        }
        ARMORED.add(wither);
    }

    /**
     * 关闭该凋灵的护甲，并抑制后续自动标记。
     *
     * <p><b>调用时机：</b>由调试命令 {@code /wboss armor off} 调用。
     *
     * <p><b>副作用：</b>调用后 {@link #hasArmor} 返回 false，且 {@link #markArmored}
     * 不再生效，直到 {@link #restoreArmor} 被调用。
     *
     * <p><b>为什么抑制是必需的：</b>二阶段每帧都会调用 markArmored，若只清标记而不管制，
     * 下一帧护甲就会恢复。
     *
     * @param wither 目标凋灵
     */
    public static void clearArmor(WitherBoss wither) {
        SUPPRESSED.add(wither);
        ARMORED.remove(wither);
    }

    /**
     * 恢复该凋灵的护甲，并解除抑制。
     *
     * <p><b>调用时机：</b>由调试命令 {@code /wboss armor on} 调用；也在
     * {@code WitherBrain.debugForcePhaseOne()} 里用于清理调试残留，避免一次调试把护甲
     * 永久关死。
     *
     * <p><b>副作用：</b>移除抑制标志并重新加入生效集合。
     *
     * @param wither 目标凋灵
     */
    public static void restoreArmor(WitherBoss wither) {
        SUPPRESSED.remove(wither);
        ARMORED.add(wither);
    }

    /**
     * 查询该凋灵是否处于"护甲被显式关闭"的状态。
     *
     * <p><b>调用时机：</b>调试信息 dump 输出时，用来区分"没有护甲"与"被命令关掉了"。
     *
     * @param wither 目标凋灵
     * @return true 表示护甲被命令/调试关闭
     */
    public static boolean isSuppressed(WitherBoss wither) {
        return SUPPRESSED.contains(wither);
    }

    /**
     * 查询该凋灵当前是否处于护甲生效状态。
     *
     * <p><b>调用时机：</b>弹射物命中判断时由外部的 ProjectileImpactEvent 处理器查询；
     * 命中凋灵的弹射物若返回 true 就被取消。
     *
     * @param wither 目标凋灵
     * @return true 表示本次命中应被挡下
     */
    public static boolean hasArmor(WitherBoss wither) {
        return ARMORED.contains(wither);
    }

    /**
     * 凋灵死亡时清理两个集合里的条目。
     *
     * <p><b>调用时机：</b>凋灵死亡/移除的清理流程中。
     *
     * @param wither 目标凋灵
     */
    public static void forget(WitherBoss wither) {
        ARMORED.remove(wither);
        SUPPRESSED.remove(wither);
    }

    // ---------------------------------------------------------------
    //  血条（Boss 事件）
    // ---------------------------------------------------------------

    /**
     * 更新 Boss 血条。
     *
     * <p><b>调用时机：</b>WitherBrain 每帧刷新血条时调用（拿血条的入口是
     * WitherBossAccessor.fusion$getBossEvent）。
     *
     * <p><b>为什么进度要自己算：</b>原版那套血条更新随原版 AI 的被接管而不再可靠，
     * 因此进度改由本类按 {@code 当前血量 / 最大血量} 主动写入，并用 {@code Math.max(1.0F, ...)}
     * 兜底 —— 最大血量为 0 时（理论上不会，但配置改动可能触发）防止除零得到 NaN。
     *
     * <p><b>为什么颜色随阶段与攻击欲望变化：</b>这是验收标准第 1 条要的「视觉提示」的一部分 ——
     * 玩家不用看血条数字，也能从血条颜色感觉到它变凶了；二阶段与狂暴用红/紫，避免互相混淆。
     *
     * <p><b>副作用：</b>仅改血条的进度与颜色，不改血量本身。
     *
     * @param wither 目标凋灵
     * @param bar 该凋灵的 Boss 血条；为 null 时直接返回（未绑定血条的情况）
     * @param phaseTwo 是否已进入二阶段
     * @param highDesire 当前是否处于高攻击欲望（狂暴模式）
     */
    public static void updateBossBar(WitherBoss wither, ServerBossEvent bar, boolean phaseTwo, boolean highDesire) {
        if (bar == null) {
            return;
        }
        bar.setProgress(wither.getHealth() / Math.max(1.0F, wither.getMaxHealth()));
        // 二阶段用红/紫区分高欲望；一阶段用紫/白，白为"冷静"基线色
        if (phaseTwo) {
            bar.setColor(highDesire ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.PURPLE);
        } else {
            bar.setColor(highDesire ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.WHITE);
        }
    }
}

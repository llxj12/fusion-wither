/*
 * 【文件职责】把凋灵的状态机、护甲、死亡演出接到 Forge 事件总线上，并注册 /wboss 调试命令。
 * 【所属模块】wither 核心。
 * 【协作关系】模组入口把本类注册进游戏总线后，Forge 每 tick 回调 onLivingTick → tick() 驱动
 *   WitherBrain；伤害/弹丸/爆炸事件分别转交 WitherDeathSequence、WitherArmor、CrashEffects。
 * 【关键约束】全部回调只处理服务端的 WitherBoss；客户端实例一律早退，避免专用服务器启动或运行时崩溃。
 */
package com.fusionwither.wither;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.fusionwither.NewMod;
import com.fusionwither.wither.mixin.WitherBossAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 凋灵 Boss 的 Forge 事件接线层 + {@code /wboss} 调试命令注册器。
 *
 * <p><b>职责边界：</b>本类不做战斗决策，只负责"把原版/Forge 的时机转发给状态机"。
 * 真正的 AI、位移、射击、阶段判定都在 {@link WitherBrain} 及其行为类里；本类只做
 * "谁的 tick 该被转给谁" + "哪个事件该在哪个环节介入"。
 *
 * <p><b>为什么由事件驱动（而非注入原版方法）：</b>原设计靠 Mixin 注入
 * {@code customServerAiStep} 来驱动状态机，实机出现过"凋灵站着不动、不进二阶段"；
 * 改用 {@link #onLivingTick} 的 {@code LivingTickEvent} 后不再依赖任何注入点，
 * 调用时机由 Forge 保证。详见该方法说明。
 *
 * <p><b>关于 {@code /wboss}：</b>这是规格要求的调试入口，意义在于让"接线是否正确"
 * 与"手感是否可调"可以分开验证 —— 详见 {@link #onRegisterCommands}。
 */
public final class WitherBossHandler {

    /** 私有构造：纯静态工具类，禁止实例化。 */
    private WitherBossHandler() {}

    /** tick 异常累计次数；仅用于把日志限流为前 5 次，避免每 tick 复现的异常刷爆日志。 */
    private static int tickErrors = 0;

    // ===============================================================
    //  每 tick 入口：由 onLivingTick 回调，主体逻辑转交 WitherBrain
    // ===============================================================

    /**
     * 驱动凋灵状态机的单 tick 逻辑 —— 本类每 tick 唯一真正做事的方法。
     *
     * <p><b>调用时机：</b>由 {@link #onLivingTick} 在服务端每 tick 调一次；实体已死/正在死时走清理分支。
     *
     * <p><b>副作用：</b>补写原版 target 字段、调用 {@code brain.tick()}、刷新 Boss 血条进度；
     * 死亡或移除时清空 brain 与各附属缓存（护甲、骷髅池、死亡演出计时）。
     *
     * @param wither 本 tick 要处理的凋灵实体，非 null（调用方已过滤类型与端别）
     */
    public static void tick(WitherBoss wither) {
        // 取得该凋灵本 tick 的状态机实例（按实体缓存，见 WitherBrain.of）。
        WitherBrain brain = WitherBrain.of(wither);

        if (wither.isDeadOrDying()) {
            // 死亡/移除分支：正常路径不会再走到 brain.tick()，所以必须在这里显式收尾，
            // 否则附属状态会永远停在"进行中"，直到实体被卸载/重载。
            brain.forceClearIntroInvulnerability();
            WitherArmor.forget(wither);
            WitherSkeletonPool.clear(wither);
            WitherBrain.forget(wither);
            // 死亡演出的生命周期与 brain 绑定（见 WitherDeathSequence 的缓存说明），
            // 因此这里必须一并注销，否则 WeakHashMap 里会留下悬空的演出计时。
            WitherDeathSequence.forget(wither);
            return;
        }

        // 原版 AI 的走位被本模组接管后，目标选择也需要自己兜底：
        // 这里把 WitherBrain.targetEntity() 的结果写回原版 target 字段，
        // 使原版仍在运行的三头射击与目标选择器能按它运转。
        // 只在当前目标为空或已死时才写 —— 因此"玩家优先级高于其它生物"这件事
        // 完整地由原版自身保证，这里不会覆盖一个仍然存活的原版目标。
        if (wither.getTarget() == null || !wither.getTarget().isAlive()) {
            LivingEntity p = brain.targetEntity();
            if (p != null) {
                wither.setTarget(p);
            }
        }

        // 本 tick 的状态机主体：阶段判定、索敌、行为池、射击、回血、演出推进都在里面。
        brain.tick();

        // Boss 血条：原版 AI（customServerAiStep）已停用，血条进度改由本模组手动喂，
        // 颜色同时表达阶段与攻击欲望。
        //
        // 【出生苏醒例外】苏醒窗口内不喂，把血条交还原版 ——
        // 原版在这 220 tick 里用血条画"充能倒计时"（bossEvent.setProgress(1 - t/220)），
        // 那是激活动画最直观的可见部分；若我们每帧按血量覆盖成满格，
        // 玩家只会看到一根从头到尾不动的满血条。
        // 判据直接复用原版字段：invulnerableTicks > 0 即处于原版苏醒窗口内。
        if (wither.getInvulnerableTicks() <= 0) {
            WitherArmor.updateBossBar(wither, bossEvent(wither),
                    brain.isPhaseTwo(), brain.isHighDesire());
        }
    }

    /**
     * 事件驱动的凋灵状态机入口 —— 实机反馈后确定的架构（不再注入 {@code customServerAiStep}）。
     *
     * <p><b>为什么改挂 {@code LivingTickEvent}：</b>实机曾出现"凋灵站着不动、不进二阶段、
     * 行为池不工作"，而血量已低于阈值 —— 说明 {@code brain.tick()} 根本没在跑。
     * 原注入点 {@code Mob.m_8024_} 虽能通过编译与启动校验，但运行时的真实调用时机与条件
     * 无法离线确证，风险过高；{@code LivingTickEvent} 是玩家/实体每 tick 稳定触发的 Forge 事件，
     * 不依赖任何注入点。
     *
     * <p><b>前置条件：</b>只处理服务端的 {@code WitherBoss}；非凋灵、客户端实例直接早退。
     *
     * @param event Forge 每 tick 广播的活体 tick 事件；实体非目标类型时本方法立即返回
     */
    @SubscribeEvent
    public static void onLivingTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof WitherBoss wither)) {
            return;
        }
        if (wither.level().isClientSide()) {
            return;
        }
        // 状态机任何异常都不允许冒泡回原版 tick（会打断整条实体 tick 链），统一交给限流日志。
        try {
            tick(wither);
        } catch (Throwable t) {
            logTickError(t);
        }
    }

    /**
     * 致死一击改写钩子（游戏总线：{@code LivingDamageEvent}）—— 基岩版式死亡演出的入口。
     * 作用是把"会把血量打到 0 的那一击"改写成"刚好压到 1.0"，从而把死亡权从原版手里接管过来。
     *
     * <p><b>为什么挂 {@code LivingDamageEvent}：</b>它与 {@code LivingHurtEvent} 都在
     * 原版 {@code LivingEntity.m_6475_}（actuallyHurt）内部触发，但前者拿到的 {@code getAmount()}
     * 是"扣完护甲与吸收、真正要从血量里减掉的那个值"，因此 {@code amount >= getHealth()}
     * 就是"这一击会打死它"的精确判据；后者早于减免，会把"被护甲挡下的一击"误判成致死。
     *
     * <p><b>为什么用 {@code setAmount} 而不是 {@code setCanceled}：</b>
     * Forge 把该事件 hook 的返回值直接当作新的伤害量（{@code ForgeHooks.onLivingDamage} 返回 float，
     * 由调用方字节码写回局部变量），所以把值改成 0 就等于完全免伤，比取消事件更直接。
     *
     * <p><b>副作用：</b>不修改实体自身状态，只重写本次伤害量；具体判定与改写规则见
     * {@link WitherDeathSequence#filterDamage}。
     *
     * @param event 伤害事件；实体非凋灵或客户端实例时本方法立即返回
     */
    @SubscribeEvent
    public static void onLivingDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof WitherBoss wither)) {
                return;
            }
            if (wither.level().isClientSide()) {
                return;
            }
            // 交由死亡演出判断：致命一击被垫成"压血到 1.0"、非致命一击原样放行。
            event.setAmount(WitherDeathSequence.filterDamage(
                    wither, event.getSource(), event.getAmount()));
        } catch (Throwable t) {
            logTickError(t);
        }
    }

    /**
     * 取凋灵原版持有的 Boss 血条对象（{@code ServerBossEvent}）。
     *
     * <p><b>为什么需要访问器：</b>原版该字段是私有的，只能通过 {@link WitherBossAccessor}
     * 的 mixin 访问器读出；读不到（非原版实例）时返回 null，由 {@code updateBossBar} 自行容错。
     *
     * @param wither 目标凋灵
     * @return 该凋灵的血条事件对象；读不到时为 null
     */
    private static ServerBossEvent bossEvent(WitherBoss wither) {
        if (wither instanceof WitherBossAccessor accessor) {
            return accessor.fusion$getBossEvent();
        }
        return null;
    }

    /**
     * 记录状态机异常，并把日志限流为前 5 次，避免异常风暴刷爆日志。
     *
     * <p><b>为什么限流：</b>状态机在 tick 里抛异常会每 tick 复现，无限打印会让日志不可读；
     * 只保留前 5 条足以定位根因。
     *
     * @param t 在 tick 或事件回调中捕获到的异常
     */
    public static void logTickError(Throwable t) {
        if (tickErrors < 5) {
            tickErrors++;
            NewMod.LOGGER.error("[凋灵] 状态机在 tick 中抛异常（第 {} 次）", tickErrors, t);
        }
    }

    // ===============================================================
    //  护甲：只免疫弹射物（近战与爆炸按规格仍然有效）
    // ===============================================================

    /**
     * 弹射物命中拦截（游戏总线：{@code ProjectileImpactEvent}）——
     * 二阶段护甲的"只挡弹射物"语义在此落地。
     *
     * <p><b>调用时机：</b>任何弹射物（箭、弩、三叉戟、雪球、火球……）命中实体/方块时触发，
     * 因此这里不按弹射物类型过滤。近战走 {@code mobAttack}、爆炸走 {@code explode}，
     * 都不经过本事件，天然不受护甲影响（规格 G2/G3）。
     *
     * <p><b>副作用：</b>命中凋灵且护甲开启时，把命中结果设为 {@code STOP_AT_CURRENT_NO_DAMAGE}
     * （停在此处且不结算伤害），并在命中点播放护甲弹开特效；同时无条件清理本模组弹幕的标记。
     *
     * @param event 弹射物命中事件
     */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        try {
            // F-07：清理本模组弹幕的标记。
            // 早先只在「本模组弹幕击中凋灵」这一最窄条件下清理，
            // 击中玩家或方块的弹幕标记会残留在 WeakHashMap 中。
            // 现在提到最前面，且不再限定命中目标。
            if (event.getProjectile() instanceof WitherSkull ourSkull) {
                SkullVolley.forget(ourSkull);
            }

            // F-06：护甲免疫的是「弹射物」，不是「凋灵之首」。
            // 因此这里不限制弹射物类型 —— 弓、弩、三叉戟、雪球、火球等
            // 全部由本事件经过，一律在此判断。
            // 近战走 mobAttack（不触发本事件）、爆炸走 explode（不触发本事件），
            // 因此两者天然不受影响（规格 G2/G3）。
            if (event.getRayTraceResult() instanceof EntityHitResult hit
                    && hit.getEntity() instanceof WitherBoss target
                    && WitherArmor.hasArmor(target)) {
                // 用现代 API 替代已过时的 setCanceled(boolean)：
                // STOP_AT_CURRENT_NO_DAMAGE = 停在此处且不造成伤害，
                // 与旧行为「完整取消（停止 + 不结算伤害）」语义一致；
                // 区别是旧行为会中断整个实体循环，新行为只处理当前实体。
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
                VisualFeedback.armorDeflected(target.level(),
                        hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
            }
        } catch (Throwable t) {
            logTickError(t);
        }
    }

    /**
     * 爆炸方块过滤（游戏总线：{@code ExplosionEvent.Detonate}）——
     * 在爆炸真正破坏方块前，把受保护方块从破坏列表里剔除（F-09 的名单在此生效）。
     *
     * <p><b>为什么只处理本模组凋灵的爆炸：</b>按爆炸源实体判定，只拦截 {@code WitherBoss}
     * 引发的爆炸，因此不会影响其它模组或原版凋灵的爆炸行为。
     *
     * <p><b>副作用：</b>原地修改 {@code event.getAffectedBlocks()}，不改变爆炸威力与伤害结算。
     *
     * @param event 爆炸引爆事件（爆炸刚决定要破坏哪些方块时触发）
     */
    @SubscribeEvent
    public static void onExplosionDetonate(net.minecraftforge.event.level.ExplosionEvent.Detonate event) {
        try {
            if (!(event.getExplosion().getIndirectSourceEntity() instanceof WitherBoss)) {
                return;
            }
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                CrashEffects.filterDetonation(event.getAffectedBlocks(), serverLevel);
            }
        } catch (Throwable t) {
            logTickError(t);
        }
    }

    // ===============================================================
    //  调试命令 /wboss：验收与手感调参的唯一入口
    // ===============================================================

    /**
     * 注册 {@code /wboss} 调试命令树（游戏总线：{@code RegisterCommandsEvent}）。
     *
     * <p><b>调用时机：</b>服务器每次装载数据包/命令树时触发一次；权限等级 ≥ 2 的执行者才可见。
     *
     * <p><b>子命令一览：</b>
     * <ul>
     *   <li>{@code dump} —— 打印阶段/血量/欲望/当前行为/索敌/CD/各演出状态，是验收主工具；</li>
     *   <li>{@code diag} —— 逐项暴露"玩家打不动"相关的状态字段，用于实测定位根因；</li>
     *   <li>{@code spawn} —— 在眼前 6 格生成一只带出生苏醒动画的凋灵；</li>
     *   <li>{@code phase 1|2} —— 强制切换阶段；</li>
     *   <li>{@code act &lt;barrage|crash|none&gt;} —— 强制某个行为，或清空；</li>
     *   <li>{@code cd &lt;ticks&gt;} —— 直接设两个行为 CD，用来造空窗期；</li>
     *   <li>{@code armor on|off} —— 开关二阶段护甲；</li>
     *   <li>{@code hp &lt;percent&gt;} —— 直接设血量，验证阶段切换；</li>
     *   <li>{@code desire on|off} —— 把玩家血量压低/回满，验证欲望切换。</li>
     * </ul>
     *
     * @param event 命令注册事件，提供 {@code CommandDispatcher}
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("wboss")
                .requires(s -> s.hasPermission(2))
                // ---- dump：打印当前完整状态，验收时的主要工具 ----
                .then(Commands.literal("dump").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    WitherBoss w = nearestWither(src);
                    if (w == null) {
                        return fail(src);
                    }
                    WitherBrain b = WitherBrain.of(w);
                    String active = b.activeBehavior() == null ? "空窗期" : b.activeBehavior().name();
                    // 索敌状态：它是行为池的总闸门 —— targetEntity() 返回 null 时
                    // WitherBrain.tick() 会让技能/走位/射击整段停摆。
                    // 优先级为「原版仇恨的玩家 → 64 格内玩家 → 原版仇恨的生物 → 无」，
                    // 因此这里显示的就是行为池真正在打的那个对象
                    // （复查"无仇恨仍走位"一类问题时，先看这一项）。
                    LivingEntity tp = b.targetEntity();
                    String aggro = tp == null
                            ? "无(未索敌·行为池停摆)"
                            : tp.getType().getDescription().getString() + "·" + tp.getName().getString();
                    src.sendSuccess(() -> Component.literal(String.format(
                            "[凋灵] 阶段=%s 血量=%.1f/%.1f(%.0f%%) 欲望=%s 当前行为=%s 索敌=%s",
                            b.isPhaseTwo() ? "二阶段" : "一阶段",
                            w.getHealth(), w.getMaxHealth(), b.healthRatio() * 100.0D,
                            b.isHighDesire() ? "高攻击" : "消耗", active, aggro)), false);
                    src.sendSuccess(() -> Component.literal(String.format(
                            "[凋灵] CD: 弹幕=%d 冲撞=%d 软=%d | 入场演出=%s 出生苏醒=%s 死亡演出=%s 二段入场=%s 护甲=%s 骷髅=%d/%d | 相邻重复率=%.0f%% | 已进二阶段=%s",
                            b.getBarrageCd(), b.getCrashCd(), b.getCrashSoftCd(),
                            b.isIntroPlaying() ? "进行中" : "无",
                            b.isAwakening() ? "进行中" : "无",
                            WitherDeathSequence.describe(w),
                            b.isPhaseTwoFalling()
                                    ? String.format("坠落中(%d tick)", b.phaseTwoFallTicksLeft())
                                    : "无",
                            WitherArmor.isSuppressed(w) ? "关(已抑制)"
                                    : (WitherArmor.hasArmor(w) ? "开" : "关"),
                            WitherSkeletonPool.aliveCount(w),
                            WitherConfig.PHASE2_SKELETON_MAX_ALIVE,
                            b.adjacentRepeatRate() * 100.0D,
                            b.hasEnteredPhaseTwo() ? "是" : "否")), false);
                    return 1;
                }))
                // ---- diag：伤害诊断（查"玩家打不动"用）----
                // 逐项列出所有可能导致"玩家打不动凋灵"的状态。
                // 由实机反馈新增：两轮反馈近战无效，但静态分析无法确定根因，
                // 因此把可观测量全部暴露出来，用一次实测定位。
                .then(Commands.literal("diag").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    WitherBoss w = nearestWither(src);
                    if (w == null) {
                        return fail(src);
                    }
                    // 1) 关键状态字段（血量 / 无敌帧 / 无敌标志）
                    src.sendSuccess(() -> Component.literal(String.format(
                            "[诊断] 血量=%.2f/%.2f  无敌帧(invulnerableTime)=%d  无敌标志(invulnerable)=%s",
                            w.getHealth(), w.getMaxHealth(),
                            w.invulnerableTime, w.isInvulnerable())), false);
                    // 2) 相关属性（护甲值 / 击退抗性）
                    var armor = w.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
                    var kb = w.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
                    src.sendSuccess(() -> Component.literal(String.format(
                            "[诊断] 护甲值=%s  击退抗性=%s  是否死亡中=%s",
                            armor == null ? "无" : String.valueOf(armor.getValue()),
                            kb == null ? "无" : String.valueOf(kb.getValue()),
                            w.isDeadOrDying())), false);
                    // 3) 本模组状态（阶段 / 各演出计时 / 心跳计数 / 护甲标记）
                    WitherBrain b = WitherBrain.of(w);
                    src.sendSuccess(() -> Component.literal(String.format(
                            "[诊断] 阶段=%s 出生苏醒=%s(剩余 %d tick) 演出剩余=%d tick 状态机心跳=%d 死亡演出=%s 二段入场=%s 护甲标记=%s 已抑制=%s",
                            b.isPhaseTwo() ? "二阶段" : "一阶段",
                            b.isAwakening() ? "进行中" : "无",
                            w.getInvulnerableTicks(),
                            b.introTicksLeft(),
                            b.tickCount(),
                            WitherDeathSequence.describe(w),
                            b.isPhaseTwoFalling()
                                    ? String.format("坠落中(%d tick)", b.phaseTwoFallTicksLeft())
                                    : "无",
                            WitherArmor.hasArmor(w) ? "开" : "关",
                            WitherArmor.isSuppressed(w) ? "是" : "否")), false);
                    src.sendSuccess(() -> Component.literal(
                            "[诊断] 心跳每次调用应 +1：隔几秒连打两次 diag，若数字不长 = 状态机没在跑。"),
                            false);
                    src.sendSuccess(() -> Component.literal(
                            "[诊断] 若「无敌帧」持续 > 10，原版会丢弃小额重复伤害 —— 那就是打不动的原因。"),
                            false);
                    return 1;
                }))
                // ---- spawn：眼前 6 格生成一只凋灵（带出生苏醒动画）----
                .then(Commands.literal("spawn").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer p = src.getPlayerOrException();
                    ServerLevel level = (ServerLevel) p.level();
                    WitherBoss w = EntityType.WITHER.create(level);
                    if (w == null) {
                        src.sendFailure(Component.literal("[凋灵] 创建凋灵失败"));
                        return 0;
                    }
                    w.moveTo(p.getX() + 6.0D, p.getY(), p.getZ(), 0.0F, 0.0F);
                    level.addFreshEntity(w);
                    // 【出生苏醒】原版只把这段演出挂在"灵魂沙 + 凋灵首级"的结构召唤上
                    // （WitherSkullBlock → WitherBoss.m_31506_：invulnerableTicks = 220）。
                    // 用 create + addFreshEntity 直接生成的凋灵该字段是 0，
                    // 按原版规则本来就没有激活动画 —— 这里补上等价状态，
                    // 让"凋灵激活时的动画"在调试命令下同样可以看到：
                    // 血条从 0 充能到满、凋灵周身灵魂粒子、11 秒内免伤，
                    // 倒计时归零时在原位炸一发威力 7 的爆炸并广播 1023 吼声。
                    // 状态机在这段时间会自动早退（见 WitherBrain.updateAwakening）。
                    w.setInvulnerableTicks(WitherConfig.WITHER_AWAKENING_TICKS);
                    src.sendSuccess(() -> Component.literal(String.format(
                            "[凋灵] 已生成凋灵，血量上限=%.0f | 出生苏醒动画 %d tick（结束时原位爆炸 + 吼声，请站远些看）",
                            w.getMaxHealth(), WitherConfig.WITHER_AWAKENING_TICKS)), true);
                    return 1;
                }))
                // ---- phase 1|2：强制切换阶段 ----
                .then(Commands.literal("phase")
                        .then(Commands.literal("1").executes(ctx -> {
                            WitherBoss w = nearestWither(ctx.getSource());
                            if (w == null) return fail(ctx.getSource());
                            WitherBrain.of(w).debugForcePhaseOne();
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("[凋灵] 已强制回到第一阶段"), false);
                            return 1;
                        }))
                        .then(Commands.literal("2").executes(ctx -> {
                            WitherBoss w = nearestWither(ctx.getSource());
                            if (w == null) return fail(ctx.getSource());
                            WitherBrain.of(w).debugForcePhaseTwo();
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("[凋灵] 已强制进入第二阶段"), false);
                            return 1;
                        })))
                // ---- act <barrage|crash|none>：强制行为（none = 清空行为与 CD）----
                .then(Commands.literal("act")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    b.suggest("barrage");
                                    b.suggest("crash");
                                    b.suggest("none");
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    WitherBoss w = nearestWither(ctx.getSource());
                                    if (w == null) return fail(ctx.getSource());
                                    String name = StringArgumentType.getString(ctx, "name");
                                    WitherBrain brain = WitherBrain.of(w);
                                    if ("none".equalsIgnoreCase(name)) {
                                        brain.debugResetCooldowns();
                                        ctx.getSource().sendSuccess(() ->
                                                Component.literal("[凋灵] 已清空当前行为与所有 CD"), false);
                                    } else {
                                        brain.debugForceBehavior(name);
                                        ctx.getSource().sendSuccess(() ->
                                                Component.literal("[凋灵] 已强制行为：" + name), false);
                                    }
                                    return 1;
                                })))
                // ---- cd <ticks>：把两个行为 CD 一起设为该值（0 = 立刻可放，用来造空窗期）----
                .then(Commands.literal("cd")
                        .then(Commands.argument("ticks", DoubleArgumentType.doubleArg(0.0D))
                                .executes(ctx -> {
                                    WitherBoss w = nearestWither(ctx.getSource());
                                    if (w == null) return fail(ctx.getSource());
                                    int ticks = (int) DoubleArgumentType.getDouble(ctx, "ticks");
                                    WitherBrain.of(w).debugForceCooldowns(ticks);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "[凋灵] 两个行为 CD 已设为 " + ticks
                                                    + " tick（0 = 立刻可放）"), false);
                                    return 1;
                                })))
                // ---- armor on|off：调试用开关护甲（V-18 接入）----
                // 注意：不能只调用 clearArmor —— 二阶段每帧都会 markArmored，
                // 下一帧护甲就回来了。因此 clearArmor 内部会置抑制标志，
                // 而 markArmored 内部会检查该标志，从而让"关闭"真正生效。
                .then(Commands.literal("armor")
                        .then(Commands.literal("off").executes(ctx -> {
                            WitherBoss w = nearestWither(ctx.getSource());
                            if (w == null) return fail(ctx.getSource());
                            WitherArmor.clearArmor(w);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[凋灵] 护甲已关闭并抑制自动标记 —— dump 的「护甲」字段应显示：关。"
                                            + "用 /wboss armor on 恢复。"), false);
                            return 1;
                        }))
                        .then(Commands.literal("on").executes(ctx -> {
                            WitherBoss w = nearestWither(ctx.getSource());
                            if (w == null) return fail(ctx.getSource());
                            WitherArmor.restoreArmor(w);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[凋灵] 护甲已恢复 —— dump 的「护甲」字段应显示：开。"), false);
                            return 1;
                        })))
                // ---- hp <percent>：直接设凋灵血量（百分比），验证阶段切换 ----
                .then(Commands.literal("hp")
                        .then(Commands.argument("percent", DoubleArgumentType.doubleArg(1.0D, 100.0D))
                                .executes(ctx -> {
                                    WitherBoss w = nearestWither(ctx.getSource());
                                    if (w == null) return fail(ctx.getSource());
                                    double pct = DoubleArgumentType.getDouble(ctx, "percent");
                                    w.setHealth((float) (w.getMaxHealth() * pct / 100.0D));
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "[凋灵] 凋灵血量已设为 %.0f%%（%.1f）", pct, w.getHealth())), false);
                                    return 1;
                                })))
                // ---- desire on|off：把玩家血量压到半血以下 / 回满，验证欲望切换 ----
                .then(Commands.literal("desire")
                        .then(Commands.literal("on").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            p.setHealth(Math.max(1.0F, p.getMaxHealth() * 0.4F));
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[凋灵] 玩家血量已压到 40% —— 观察凋灵是否在 2 秒内切到高攻击欲望"), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            p.setHealth(p.getMaxHealth());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[凋灵] 玩家血量已回满 —— 观察凋灵是否在 2 秒内切回消耗模式"), false);
                            return 1;
                        }))));
    }

    /**
     * 统一的"找不到凋灵"失败输出。
     *
     * @param src 命令执行者
     * @return 固定返回 0，作为 Brigadier 约定表示命令失败
     */
    private static int fail(CommandSourceStack src) {
        src.sendFailure(Component.literal("[凋灵] 附近 64 格内没有凋灵"));
        return 0;
    }

    /**
     * 找命令执行者 64 格内最近的凋灵，作为所有子命令的操作对象。
     *
     * <p><b>为什么取"最近"而不是"准星指向"：</b>调试时只需确保场内有一只可操作的凋灵，
     * 免去对准操作的成本。玩家不存在（控制台执行）或查询异常时返回 null，由各子命令用
     * {@link #fail} 兜底。
     *
     * @param src 命令执行者，需为玩家
     * @return 64 格内首个凋灵；找不到或非玩家执行时为 null
     */
    private static WitherBoss nearestWither(CommandSourceStack src) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            return p.level().getEntitiesOfClass(WitherBoss.class,
                            p.getBoundingBox().inflate(64.0D))
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}

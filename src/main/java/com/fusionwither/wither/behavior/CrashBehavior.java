/*
 * 【文件职责】二阶段行为池 · 行为B「冲撞」：蓄力锁定方向 → 高速直线冲锋并沿途破坏 → 命中爆炸 → 垂直上升到 8~12 格。
 * 【所属模块】wither.behavior 行为池。
 * 【协作关系】继承 BaseBehavior，由 WitherBrain 二阶段派发逻辑按权重选中后 begin()，每帧 tick()；
 *             位移经 WitherBrain.chargeForward / riseStep（坐标所有权），爆炸经 CrashEffects，视觉表现经 VisualFeedback。
 * 【关键约束】上升是玩家唯一的稳定输出窗口，且「冲撞 + 上升」为一个完整行为 —— 上升结束前行为池不会切别的行为。
 */
package com.fusionwither.wither.behavior;

import com.fusionwither.wither.CrashEffects;
import com.fusionwither.wither.VisualFeedback;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 行为池 · 行为 B：<b>冲撞</b>。
 *
 * <p>一次完整行为分五态推进（蓄力 → 冲锋 → 命中 → 上升 → 结束），其中两段是刻意留给玩家的：
 * <ul>
 *   <li><b>蓄力</b> {@code CRASH_WINDUP_TICKS}(8t) —— 锁定方向、原地不动，且朝向对准冲锋方向，玩家可离开瞄准线规避</li>
 *   <li><b>冲锋</b> —— 直线高速（{@code CRASH_SPEED} 2.2 格/tick），命中、连续撞墙或到最大距离即结束</li>
 *   <li><b>爆炸</b> —— 伤害 + 击退 + 破坏地形（受黑名单约束）</li>
 *   <li><b>上升</b> {@code 8~12} 格 —— 不攻击、护甲保持、轨迹可预测，<b>这就是玩家稳定的输出窗口</b></li>
 * </ul>
 * 「冲撞 + 上升为一个完整行为」—— 因此上升段走完前，行为池不会切别的行为（active 不释放）。
 *
 * <p><b>权重：</b>由 WitherBrain 按 {@code WEIGHT_CRASH_NORMAL}(50) / {@code WEIGHT_CRASH_HIGH}(70)
 * 与弹幕压制加权随机竞争；{@code desire} ≥ 0.5（血量劣势更大）时用 HIGH 权重组，此外还需通过硬 CD + 软 CD 两道闸门。
 *
 * <p><b>为什么不在此重复判 isCreative()/isSpectator()：</b>目标合法性（存活、非创造、非旁观）
 * 已由唯一索敌入口 {@link com.fusionwither.wither.WitherBrain#targetEntity()} 统一保证。
 * 同一语义在两处实现必然行为漂移（本项目反复出现的问题），所以这里只消费 {@code target()}。
 */
public final class CrashBehavior extends BaseBehavior {

    /** 冲撞的五个阶段：蓄力 → 冲锋 → 命中爆炸 → 上升 → 结束。 */
    private enum State { WINDUP, CHARGE, IMPACT, RISE, DONE }

    /** 当前阶段；onBegin 复位为 WINDUP，onTick 内按条件推进，DONE 时 checkFinished 判定结束。 */
    private State state = State.WINDUP;
    /** 本阶段剩余 tick（蓄力倒计时、上升超时预算复用此字段语义分阶段使用）。 */
    private int timer = 0;

    /** 蓄力瞬间锁定的冲撞方向（水平单位向量）—— 之后不再追踪玩家，可规避性就来自这里。 */
    private Vec3 lockedDir = Vec3.ZERO;
    /** 冲锋起点，用于计算已冲距离（判定是否达到最大距离）。 */
    private Vec3 chargeStart = Vec3.ZERO;
    /** 上升段的目标高度（Y 坐标绝对值）。 */
    private double riseTargetY = 0.0D;
    /** 上升段剩余的超时预算（tick）；减到 0 就强制结束 RISE，防止被天花板/方块挡住时永久卡住。 */
    private int riseTimeout = 0;
    /** 冲锋阶段连续被挡的 tick 数（撞墙检测用）：连续 2 tick 没推动即判定撞墙结束冲锋。 */
    private int blockedTicks = 0;

    /** 行为名（日志与调试命令用）。 */
    @Override
    public String name() {
        return "冲撞";
    }

    /** 开始：复位到蓄力段并锁定冲锋方向，同时把凋零朝向也转向该方向（视觉上能看出要冲哪）。 */
    @Override
    protected void onBegin() {
        state = State.WINDUP;
        timer = WitherConfig.CRASH_WINDUP_TICKS;
        blockedTicks = 0;

        // 冲撞方向在蓄力瞬间锁定 —— 之后玩家移动就不再追踪，这是可规避性的来源
        Vec3 flat = flatToTarget();
        // 与目标水平重合（flat 退化）时取 +X 兜底，保证 lockedDir 始终是有效单位向量
        lockedDir = flat.lengthSqr() < 1.0E-6D
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : flat.normalize();
        chargeStart = brain().wither().position();
        brain().stopMoving();
        VisualFeedback.crashWindup(brain().wither(), brain().level());

        // 冲撞方向也用于朝向：由 (x,z) 反算 yaw（与 WitherBrain 锁定方向用的是同一个公式）
        float yaw = (float) (Math.atan2(-lockedDir.x, lockedDir.z) * 180.0D / Math.PI);
        brain().wither().setYRot(yaw);
        brain().wither().setYHeadRot(yaw);
    }

    /** 每帧推进当前阶段。位移一律走坐标所有权接口；各段的时间判断都在本方法内部完成。 */
    @Override
    protected void onTick() {
        switch (state) {
            case WINDUP -> {
                brain().stopMoving();
                // 蓄力表现（实机反馈：原实现「没有动画就冲了」，玩家没有预警）
                VisualFeedback.crashWindupTick(brain().wither(), brain().level());
                if (--timer <= 0) {
                    state = State.CHARGE;
                    blockedTicks = 0;
                }
            }
            case CHARGE -> {
                // 冲锋：坐标驱动直线推进（CRASH_SPEED = 2.2 格/tick）。
                // 为什么不用 deltaMovement：原版 aiStep 会把这处速度覆盖成反向并硬写 y=0，
                // 详见 WitherBrain.chargeForward 的说明。
                boolean blocked = brain().chargeForward(lockedDir, WitherConfig.CRASH_SPEED);

                // 【实机反馈新增】冲锋途中**实时破坏沿途方块**，
                // 使「冲过来撞穿一切」有实感，而不是撞墙就停。
                // 破坏仅限凋零本身能破坏的方块，且受黑名单/保护前缀约束。
                if (WitherConfig.CRASH_BREAK_BLOCKS) {
                    CrashEffects.breakAlongCharge(brain().wither(), brain().level());
                }

                VisualFeedback.crashChargeTrail(brain().wither(), brain().level());
                // 撞墙判定必须**连续**：被挡则累加，本帧没被挡就清零。
                // 若只有 if(blocked) 累加而无 else 清零，长冲锋里断续出现的单帧「被挡」
                // 会累加到 2 而误判「撞墙」提前结束。
                if (blocked) {
                    blockedTicks++;
                } else {
                    blockedTicks = 0;
                }
                double travelled = brain().wither().position().distanceTo(chargeStart);
                // 撞墙（连续 2 tick 没推动）或到最大距离 → 结束冲锋
                if (blockedTicks >= 2 || travelled >= WitherConfig.CRASH_MAX_DISTANCE) {
                    // 【诊断】冲锋位移与落点 —— 实测时据此核对「是否真的全速冲出去」。
                    com.fusionwither.NewMod.LOGGER.info(
                            "[凋零/诊断] 冲撞结束：位移={} 格，落点=({}, {}, {})，撞墙={}",
                            String.format("%.1f", travelled),
                            String.format("%.1f", brain().wither().getX()),
                            String.format("%.1f", brain().wither().getY()),
                            String.format("%.1f", brain().wither().getZ()),
                            blockedTicks >= 2);
                    state = State.IMPACT;
                }
            }
            case IMPACT -> {
                // 冲撞伤害：直接伤害 + 击退。
                // 【实机反馈修复】原先只判 `distanceTo <= 半径+1.5`，而高速冲撞可能在两帧之间
                // 「穿过」玩家所在位置，导致伤害判定落空。现在改为双保险：
                //   ① 放宽距离判定 —— 用包围盒相交（inflate 后）而不是中心点距离
                //   ② 沿冲锋路径再做一次「线段是否扫过玩家」的补充判定
                // 目标合法性（存活、非创造、非旁观）已由 WitherBrain.targetEntity() 的索敌规则
                // 统一保证，这里不再重复判 —— 同一语义两处实现必然漂移。
                LivingEntity t = target();
                if (t != null) {
                    boolean inRange = t.getBoundingBox().inflate(1.5D)
                            .intersects(brain().wither().getBoundingBox().inflate(
                                    WitherConfig.CRASH_IMPACT_RADIUS));
                    // 补充：玩家是否落在「冲锋起点 → 当前位置」这条线段的附近
                    boolean swept = distanceToSegment(t.position(), chargeStart,
                            brain().wither().position()) <= WitherConfig.CRASH_IMPACT_RADIUS;
                    if (inRange || swept) {
                        t.hurt(brain().level().damageSources().mobAttack(brain().wither()),
                                (float) WitherConfig.CRASH_IMPACT_DAMAGE);
                        // 击退：让「被撞飞」有实感；hurtMarked 置真让客户端立刻同步这次击退位移
                        Vec3 push = t.position().subtract(brain().wither().position());
                        Vec3 flat = new Vec3(push.x, 0.0D, push.z);
                        if (flat.lengthSqr() > 1.0E-6D) {
                            flat = flat.normalize();
                            t.push(flat.x * WitherConfig.CRASH_KNOCKBACK,
                                   WitherConfig.CRASH_KNOCKBACK_LIFT,
                                   flat.z * WitherConfig.CRASH_KNOCKBACK);
                            t.hurtMarked = true;
                        }
                    }
                }
                CrashEffects.crashExplosion(brain().wither(), brain().level());
                VisualFeedback.crashImpact(brain().wither(), brain().level());

                // 进入上升段：目标高度 = 当前位置 + 随机 8~12 格
                double rise = randDouble(WitherConfig.CRASH_RISE_MIN_BLOCKS,
                        WitherConfig.CRASH_RISE_MAX_BLOCKS);
                riseTargetY = brain().wither().getY() + rise;
                // 超时预算：按上升速度估算所需 tick，再乘倍率，并设一个绝对下限。
                // 到不了目标高度时靠它强制收尾 —— 否则行为永不结束、active 永不为 null、行为池永久停摆。
                int estimated = (int) Math.ceil(rise / Math.max(0.01D, WitherConfig.CRASH_RISE_SPEED));
                riseTimeout = Math.max(WitherConfig.CRASH_RISE_TIMEOUT_MIN_TICKS,
                        (int) Math.ceil(estimated * WitherConfig.CRASH_RISE_TIMEOUT_FACTOR));
                state = State.RISE;
            }
            case RISE -> {
                // 纯垂直上升，不攻击、护甲保持 —— 玩家稳定输出窗口。
                // CRASH_RISE_NO_ATTACK 控制「上升期间不攻击」：上升段本身不发起新攻击，
                // 因此该开关的实际作用是：为真时把本模组在途弹幕一并作废（标记清除），
                // 使「上升期间不攻击」在语义上真正成立。
                if (WitherConfig.CRASH_RISE_NO_ATTACK) {
                    brain().suppressOwnVolleys();
                }
                // 【实机反馈修复】上升改为**坐标驱动**，不再借原版速度搬运。
                // 缘由：本段原先 setDeltaMovement + 放行原版搬运，但原版 WitherBoss.aiStep 在
                // 「到目标水平距离 > 3 格（距离² > 9）」时会把 deltaMovement **整体覆盖**成
                // (dx*0.3 - vx*0.6, 0.0, dz*0.3 - vz*0.6) —— y 被硬写成 0。
                // 玩家一拉开距离，上升就静默失效：getY() 永远够不到 riseTargetY，
                // 只能靠 riseTimeout 兜底收尾；而且那几帧还让出了坐标控制，
                // 垂直行为完全脱离本模组约束（表现为「垂直上升、收不回来」）。
                // 改为每 tick 直接抬坐标后，上升距离只由本模组决定：到达 riseTargetY 即结束，
                // 随后 applyAltitude 立刻接回高度控制。同时不设 hasImpulse ——
                // 它会在原版 move() 里关闭方块碰撞检测，使凋零穿过天花板。
                brain().riseStep(WitherConfig.CRASH_RISE_SPEED);
                // 达标或超时即结束。超时是兜底：被天花板/方块挡住时 getY() 永远不达标。
                if (brain().wither().getY() >= riseTargetY || --riseTimeout <= 0) {
                    riseTimeout = 0;
                    state = State.DONE;
                    // 【诊断】上升段收尾 —— 实测据此核对「是否真的升到位」。
                    // 到达Y 应 ≈ 上一次 IMPACT 日志的落点Y + 8~12，随后由 applyAltitude
                    // 以每 tick ≤0.3 格落回「地面 + 2.5」。
                    com.fusionwither.NewMod.LOGGER.info(
                            "[凋零/诊断] 上升结束：到达Y={} 目标Y={}",
                            String.format("%.1f", brain().wither().getY()),
                            String.format("%.1f", riseTargetY));
                }
            }
            case DONE -> { /* 全部段已完成，等状态机在 isFinished 为真时释放本行为 */ }
        }
    }

    /** 只有进入 DONE 才算本轮行为结束；此后 WitherBrain 上冲撞 CD 并选下一个行为。 */
    @Override
    protected boolean checkFinished() {
        return state == State.DONE;
    }

    /**
     * 点到线段的最短距离（只取水平面 XZ）。
     *
     * <p>用于补充「冲撞扫过玩家」的判定：高速冲撞可能在两帧之间越过玩家位置，
     * 仅比较「当前中心距离」会漏判，需要按冲锋路径（起点 a → 当前 b）做线段距离。
     *
     * @param p 被判定点（玩家位置）
     * @param a 线段起点（冲锋起点）
     * @param b 线段终点（凋零当前位置）
     * @return 点到线段的水平最短距离（格）；线段退化（a≈b）时返回点线距离
     */
    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        double ax = a.x, az = a.z, bx = b.x, bz = b.z, px = p.x, pz = p.z;
        double dx = bx - ax, dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1.0E-6D) {
            // 起点与终点几乎重合（线段退化）→ 直接算点线距离
            double ddx = px - ax, ddz = pz - az;
            return Math.sqrt(ddx * ddx + ddz * ddz);
        }
        // t = 点 p 在 a→b 上的投影比例，钳到 [0,1] 得到线段上的最近点 (cx,cz)
        double t = ((px - ax) * dx + (pz - az) * dz) / lenSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double cx = ax + t * dx, cz = az + t * dz;
        double ex = px - cx, ez = pz - cz;
        return Math.sqrt(ex * ex + ez * ez);
    }
}

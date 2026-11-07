/*
 * 【文件职责】一阶段的 BE 位移技能（技能2）：在「不后退」的锥形内随机取 1~2 段方向，期间高速直线冲刺且不攻击。
 * 【所属模块】wither.behavior 行为池。
 * 【协作关系】由一阶段技能调度逻辑调用 begin() 启动、每 tick 调 tick()、finished() 为真时调 reset() 收尾；
 *             本类不含实例状态，段数 / 段索引 / 段内计时 / 两段方向全部存在 WitherBrain 上。
 * 【关键约束】pickForwardDirection 是全链路唯一「没有目标也能算出方向」的位移（无目标时用 randomHorizontal 兜底），
 *             必须靠 WitherBrain.tick() 位移接管段里的「无目标总闸门」把整条派发链拦下，否则会出现「没仇恨也在 BE 走位」。
 */
package com.fusionwither.wither.behavior;

import com.fusionwither.wither.WitherBrain;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.Vec3;

/**
 * 一阶段 · 技能2：BE 走位（基岩版风格的高速直线冲刺）。
 *
 * <p>工具类（全静态、无实例字段），所有进度存在 {@link WitherBrain} 上，由 begin() 与 reset() 成对管理。
 *
 * <p><b>参数（均来自配置）：</b>
 * <pre>
 *   持续：P1_BE_DASH_DURATION_TICKS —— 整段技能总时长
 *   内容：随机 1~2 段快速直线运动
 *         · 各段方向不同（第二段与第一段夹角被强制拉大 → 见 begin）
 *         · 不能向后方（远离玩家方向）运动 → 方向限制在朝向目标的 90° 半锥内
 *         · 两次之间无停顿（段内计时归零即刻切段）
 *         · 期间禁止攻击 —— 由调用方（技能调度器）据此跳过平A/弹幕
 *   速度：P1_BE_DASH_SPEED_MIN ~ MAX，远高于风筝走位
 *   互斥：与压制弹幕不能同时触发（一阶段技能调度保证）
 * </pre>
 *
 * <p><b>「不能向后方」的实现：</b>把方向限制在以「指向玩家」为轴、半张角 90° 的锥形内
 * （见 {@link WitherConfig#P1_BE_DASH_ARC_DEGREES}），即与朝向玩家的夹角始终 &lt; 90°，
 * 位移永远不会让距离变大。
 *
 * <p><b>为什么做成独立类：</b>它在一阶段是「技能」、在二阶段是「行为池成员」，
 * 共用一份实现可避免两套代码漂移 —— 同一语义在多处重复实现必然行为漂移，那正是本项目反复踩的坑。
 */
public final class BeDash {

    /** 私有构造：全静态工具类，禁止实例化。 */
    private BeDash() {}

    /** 本次技能随机选定要走的段数（1 或 2），写入 brain 供 tick/finished 读取。 */
    private static int segmentsTotal(WitherBrain brain) {
        return brain.randint(WitherConfig.P1_BE_DASH_SEGMENTS_MIN,
                WitherConfig.P1_BE_DASH_SEGMENTS_MAX);
    }

    /** 每段持续 tick = 总时长 / 段数（段间无停顿，所以各段均分整段时长）；下限 1 tick 防止除零/空转。 */
    public static int ticksPerSegment(WitherBrain brain) {
        int n = Math.max(1, brain.beDashSegments());
        return Math.max(1, WitherConfig.P1_BE_DASH_DURATION_TICKS / n);
    }

    /**
     * 技能开始：决定段数与各段方向，并把进度状态全部写回 brain。
     *
     * <p>方向在「不后退」的锥形内随机取；若段数为 2，第二段与第一段的方向夹角会被拉大（≥60°），
     * 以满足「各段方向不同」。第一段以 avoid=null 计算（只受锥形约束），第二段以第一段为 avoid 计算。
     *
     * <p>副作用：写 brain 的 beDashSegments / beDashSegmentIndex / beDashTicksLeft / beDashDir / beDashDir2，
     * 并打印一条诊断日志（用于区分「没触发」与「触发了但没走」）。
     *
     * @param brain 状态机（提供随机数与技能状态字段）
     */
    public static void begin(WitherBrain brain) {
        int n = segmentsTotal(brain);
        brain.setBeDashSegments(n);
        brain.setBeDashSegmentIndex(0);
        brain.setBeDashTicksLeft(ticksPerSegment(brain));

        Vec3 first = pickForwardDirection(brain, null);
        brain.setBeDashDir(first);
        if (n >= 2) {
            // 第二段必须与第一段方向明显不同（pickForwardDirection 内约束夹角 > 60°）
            Vec3 second = pickForwardDirection(brain, first);
            brain.setBeDashDir2(second);
        }
        // 【诊断】记录段数与每段时长 —— 实测反馈「BE 位移没生效过」时，
        // 这条日志能立刻区分「技能没触发」与「触发了但没走」。
        com.fusionwither.NewMod.LOGGER.info(
                "[凋零/诊断] BE走位开始：段数={} 每段={}tick 总时长={}tick 方向1={} 方向2={}",
                n, ticksPerSegment(brain),
                n * ticksPerSegment(brain),
                fmt(first), n >= 2 ? fmt(brain.beDashDir2()) : "—");
    }

    /** 向量水平分量简写为 "(x,z)"，仅用于上面那条诊断日志；null 显示为 "null"。 */
    private static String fmt(Vec3 v) {
        return v == null ? "null"
                : String.format("(%.2f,%.2f)", v.x, v.z);
    }

    /**
     * 每 tick 执行：沿当前段方向高速直线移动，段内计时归零后立刻切下一段（段间无停顿）。
     *
     * <p>调用时机：由一阶段技能调度每 tick 调用。位移经 {@link WitherBrain#moveBy(Vec3, double)} 登记坐标，
     * 不写 deltaMovement。
     *
     * <p>期间<b>不攻击</b> —— 调用方（技能调度器）据此跳过平A/弹幕。
     *
     * <p>副作用：推进 beDashSegmentIndex / beDashTicksLeft；索引超出段数时本帧直接不动作。
     *
     * @param brain 状态机（提供段数 / 段索引 / 段内计时 / 方向）
     */
    public static void tick(WitherBrain brain) {
        int n = Math.max(1, brain.beDashSegments());
        int idx = brain.beDashSegmentIndex();
        if (idx >= n) {
            return;
        }

        // 第一段用 beDashDir，第二段用 beDashDir2；方向丢失（如被清空）时按锥形重新取一个
        Vec3 dir = idx == 0 ? brain.beDashDir() : brain.beDashDir2();
        if (dir == null || dir.lengthSqr() < 1.0E-6D) {
            dir = pickForwardDirection(brain, null);
        }

        // 每帧重新随机速度（段内速度快慢变化），不做限幅 —— 上限由配置区间负责
        double speed = brain.randdouble(WitherConfig.P1_BE_DASH_SPEED_MIN,
                WitherConfig.P1_BE_DASH_SPEED_MAX);
        brain.moveBy(dir, speed);

        // 段内计时归零 → 推进段索引（**无停顿**）
        //
        // 【历史 bug】原先写成「if (left<=0 && idx+1<n) 推进 else left=left」：
        // 当 n=1 时 `idx+1<n` 恒为 false → 索引永不递增，而 finished() 判据是
        // `idx>=n && left<=0` 也恒为 false → 技能永不结束、卡在技能状态不再做走位决策，
        // 凋零只能按原版逻辑漂移（用户反馈的「缓慢后撤」「BE 位移没生效」）。
        //
        // 现改为：**只要段内计时归零就推进索引**；若后面还有段则重置段内计时，
        // 否则把索引置为 n、计时置 0，供 finished() 判定。
        int left = brain.beDashTicksLeft() - 1;
        if (left <= 0) {
            if (idx + 1 < n) {
                brain.setBeDashSegmentIndex(idx + 1);
                brain.setBeDashTicksLeft(ticksPerSegment(brain));
            } else {
                brain.setBeDashSegmentIndex(n);
                brain.setBeDashTicksLeft(0);
            }
        } else {
            brain.setBeDashTicksLeft(left);
        }
    }

    /** 技能是否已走完全部段数（索引达段数上限且段内计时耗尽）。 */
    public static boolean finished(WitherBrain brain) {
        return brain.beDashSegmentIndex() >= Math.max(1, brain.beDashSegments())
                && brain.beDashTicksLeft() <= 0;
    }

    /**
     * 清空本技能的<b>全部</b>内部状态（段数 / 段索引 / 段内计时 / 两段方向）。
     *
     * <p>由技能调度器在技能结束时显式调用，使「开始」与「结束」对称。
     *
     * <p><b>为什么需要它：</b>技能结束时若只做 setP1Skill(0) + 上 CD，
     * {@code beDashSegments / beDashSegmentIndex / beDashTicksLeft} 会跨帧残留。
     * 当前靠 begin() 每次重置所以不出错，但这是<b>脆弱设计</b> ——
     * 状态生命周期没有单一归属，将来任何一处改动都可能让残留值生效
     * （「同一语义两处实现 → 行为漂移」的同型问题）。
     *
     * <p>副作用：把上述字段复位到「未开始」的值。
     *
     * @param brain 状态机
     */
    public static void reset(WitherBrain brain) {
        brain.setBeDashSegments(1);
        brain.setBeDashSegmentIndex(0);
        brain.setBeDashTicksLeft(0);
        brain.setBeDashDir(Vec3.ZERO);
        brain.setBeDashDir2(Vec3.ZERO);
    }

    /**
     * 在「不后退」的锥形内随机取一个水平方向。
     *
     * <p><b>【关键】无目标时的兜底：</b>目标为 null 时用 {@link WitherBrain#randomHorizontal()} 兜底，
     * 使本方法成为全链路<b>唯一</b>「没有目标也能算出位移方向」的路径。它正是
     * 「凋零没有仇恨也在 BE 走位」这一 bug 的根因：技能内部虽然普遍有目标空判，
     * 但派发本身要拦在 {@link WitherBrain#tick()} 的「无目标总闸门」里（空判放在派发之前），
     * 否则无仇恨时照样按随机方向高速冲刺。
     *
     * <p>取值方式：在以 toward 为轴、半张角 {@link WitherConfig#P1_BE_DASH_ARC_DEGREES}/2 的锥内随机转角；
     * 若给了 avoid，则最多尝试 8 次直到与 avoid 的夹角 &gt; 60°（即 dot &lt; 0.5）。
     *
     * @param brain 状态机（提供目标、凋零实体与随机数）
     * @param avoid 需要与之拉开角度的方向（用于第二段），可为 null
     * @return 归一化后的水平单位方向
     */
    private static Vec3 pickForwardDirection(WitherBrain brain, Vec3 avoid) {
        LivingEntity t = brain.targetEntity();
        Vec3 toward;
        if (t != null) {
            WitherBoss w = brain.wither();
            toward = new Vec3(t.getX() - w.getX(), 0.0D, t.getZ() - w.getZ());
        } else {
            toward = brain.randomHorizontal();
        }
        if (toward.lengthSqr() < 1.0E-6D) {
            // 与目标水平重合 → 轴向量退化，改用随机水平方向
            toward = brain.randomHorizontal();
        }
        toward = toward.normalize();

        double half = Math.toRadians(WitherConfig.P1_BE_DASH_ARC_DEGREES * 0.5D);
        Vec3 candidate = null;
        // 随机转角在 [-half, +half]，即整体夹角不超过 90°（不会后退）
        for (int i = 0; i < 8; i++) {
            double angle = (brain.random().nextDouble() * 2.0D - 1.0D) * half;
            Vec3 v = rotateY(toward, angle);
            if (avoid == null || v.dot(avoid) < 0.5D) {   // dot < 0.5 等价于夹角 > 60°
                candidate = v;
                break;
            }
        }
        if (candidate == null) {
            // 8 次都没避开（极小概率）→ 兜底：强行取与 avoid 反向的一侧
            candidate = avoid == null ? toward : rotateY(toward, half);
        }
        return candidate.normalize();
    }

    /** 绕 Y 轴把水平向量 v 按给定弧度逆时针旋转（只在 XZ 平面，y 恒为 0）。 */
    private static Vec3 rotateY(Vec3 v, double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Vec3(v.x * c - v.z * s, 0.0D, v.x * s + v.z * c);
    }
}

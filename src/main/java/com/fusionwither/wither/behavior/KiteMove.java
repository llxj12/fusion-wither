/*
 * 【文件职责】一阶段常态风筝走位：按固定周期决策「后撤 / 前进 / 不动」，把期望位移交给坐标所有权机制落地。
 * 【所属模块】wither.behavior 行为池（本类是一阶段技能调度的一员，不是二阶段行为池成员）。
 * 【协作关系】由一阶段逻辑每 tick 调用 KiteMove.tick(brain)（目标为 null 时自身停住）；
 *             本类只登记 kiteDir / kiteSpeed 与期望位移，实际钉位置由 WitherBrain.moveBy() 写 desired 坐标、
 *             MovementMixin 注射的 aiStep TAIL 在帧末执行。
 * 【关键约束】位移必须经 WitherBrain.moveBy()（坐标所有权）；直接写 deltaMovement 会被原版 aiStep 覆盖成反向。
 */
package com.fusionwither.wither.behavior;

import com.fusionwither.wither.WitherBrain;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.Vec3;

/**
 * 一阶段 · 风筝走位（常态）。
 *
 * <p>工具类（全静态、无实例状态），所有走位状态都存在 {@link WitherBrain} 上，
 * 因此复活 / 目标切换时只需清 WitherBrain 的字段，不存在这里残留的隐藏状态。
 *
 * <p><b>节奏设计：</b>每 {@link WitherConfig#P1_KITE_DECISION_INTERVAL_TICKS} tick（默认 5t）决策一次：
 * <pre>
 *   d &lt; 6.5        → 后撤（远离玩家 + 横向分量）
 *   d &gt; 8.5        → 前进（朝玩家 + 横向分量）
 *   6.5 ≤ d ≤ 8.5  → 不动（迟滞带）
 * </pre>
 * 决策之间方向与速度都<b>锁定不变</b>，直到下一次决策帧。
 *
 * <p><b>为什么是「每 5 tick 决策一次」而不是每帧重算：</b>
 * 早先每帧重读距离、重随机速度、重算方向，后果是凋灵表现为「持续缓慢后撤」——
 * ① 距离在两格间来回跨阈值 → 方向每帧翻转 → 净位移偏向后撤；
 * ② 速度每帧重随机（0.15~0.20）→ 快慢抖动，视觉上像「慢慢飘」；
 * ③ 每帧重算方向叠加原版导航残留推力 → 系统性漂移。
 * 固定周期决策后，一次决策就是一条「稳定指令」，抖动消失且走位可预测（玩家更好读招）。
 *
 * <p><b>为什么位移不能直接写 deltaMovement：</b>原版 {@code WitherBoss.aiStep} 在水平距离平方 &gt; 9 时
 * 才写一次速度、并把 y 硬写为 0，任何自算速度都会被它覆盖。所以本类只算出方向与速度，
 * 交给 {@link WitherBrain#moveBy(Vec3, double)} 直接改坐标（登记 desired），由坐标所有权机制钉住。
 */
public final class KiteMove {

    /** 私有构造：全静态工具类，禁止实例化。 */
    private KiteMove() {}

    /**
     * 计算并执行一帧风筝走位。
     *
     * <p>调用时机：一阶段每 tick 由阶段逻辑调用。只在决策帧重新选择方向与速度，其余帧沿用上次指令 ——
     * 这正是消除「每帧翻转」抖动的关键。
     *
     * <p>副作用：可能改动 WitherBrain 的 kiteDecisionTicks / kiteDir / kiteSpeed / wallDir，
     * 并调用 moveBy() 登记本帧期望落点；目标为 null 时改为 kiteStop()（清空指令，表现为原地不动）。
     *
     * @param brain 凋灵的状态机，提供目标、配置与坐标所有权接口
     * @return 本帧实际采用的期望位移速度（格/tick）；停住时为 0.0D，供调试观察
     */
    public static double tick(WitherBrain brain) {
        WitherBoss wither = brain.wither();
        LivingEntity target = brain.targetEntity();
        if (target == null) {
            brain.kiteStop();
            return 0.0D;
        }

        // ---- 决策帧：重新读距离、定方向与速度 ----
        if (brain.kiteDecisionTicks() <= 0) {
            brain.setKiteDecisionTicks(WitherConfig.P1_KITE_DECISION_INTERVAL_TICKS);
            decide(brain, target);
        } else {
            brain.setKiteDecisionTicks(brain.kiteDecisionTicks() - 1);
        }

        // ---- 非决策帧：沿用上次指令（迟滞带内 dir/speed 为空或为 0，直接不动）----
        Vec3 dir = brain.kiteDir();
        double speed = brain.kiteSpeed();
        if (dir == null || dir.lengthSqr() < 1.0E-6D || speed <= 0.0D) {
            return 0.0D;
        }

        // ---- 撞墙：沿切向滑动，避免在墙角来回撞 ----
        Vec3 slid = slideAlongWall(brain, dir);
        if (slid.lengthSqr() < 1.0E-6D) {
            // 连切向都被挡 → 随机换向，并翻转横移方向（让下一次决策换一侧绕行）
            slid = brain.randomHorizontal();
            brain.flipStrafeSign();
            // 立刻更新指令，避免下一帧用同样的死方向再撞一次
            brain.setKiteDir(slid);
        }

        brain.moveBy(slid, speed);
        return speed;
    }

    /**
     * 做一次决策：按当前距离决定「后撤 / 前进 / 不动」，并锁定方向与速度。
     *
     * <p>带<b>迟滞带</b>：小于近阈值才后撤、大于远阈值才前进，中间区域（6.5~8.5）不动。
     * 这样凋灵不会在临界点上反复横跳 —— 那正是「持续缓慢后撤」的成因之一。
     *
     * <p>副作用：写入 brain 的 kiteDir / kiteSpeed；落入迟滞带或与目标几乎重合时改为 kiteStop()。
     *
     * @param brain 状态机（提供配置与随机数）
     * @param target 本次决策依据的目标，调用方保证非 null
     */
    private static void decide(WitherBrain brain, LivingEntity target) {
        WitherBoss wither = brain.wither();
        Vec3 here = wither.position();
        Vec3 toPlayer = new Vec3(target.getX() - here.x, 0.0D, target.getZ() - here.z);
        double dist = toPlayer.length();
        if (dist < 1.0E-4D) {
            // 与目标水平重合，方向无意义，直接停住
            brain.kiteStop();
            return;
        }
        Vec3 toward = toPlayer.normalize();
        // toward 逆时针旋转 90° 得横向量，乘 strafeSign 决定绕行方向（左右交替）
        Vec3 lateral = new Vec3(-toward.z, 0.0D, toward.x);
        double mix = WitherConfig.P1_KITE_LATERAL_MIX * brain.strafeSign();

        if (dist < WitherConfig.P1_KITE_RETREAT_DISTANCE) {
            // 太近：后撤（远离 + 横向分量，形成弧线退避而不是纯倒退）
            brain.setKiteDir(toward.scale(-1.0D).add(lateral.scale(mix)).normalize());
            brain.setKiteSpeed(brain.randdouble(WitherConfig.P1_KITE_RETREAT_SPEED_MIN,
                    WitherConfig.P1_KITE_RETREAT_SPEED_MAX));
        } else if (dist > WitherConfig.P1_KITE_APPROACH_DISTANCE) {
            // 太远：前进（朝玩家 + 横向分量）
            brain.setKiteDir(toward.add(lateral.scale(mix)).normalize());
            brain.setKiteSpeed(brain.randdouble(WitherConfig.P1_KITE_APPROACH_SPEED_MIN,
                    WitherConfig.P1_KITE_APPROACH_SPEED_MAX));
        } else {
            // 距离合适：**不动**（迟滞带）—— 避免在临界点反复进退
            brain.kiteStop();
        }
    }

    /**
     * 撞墙处理：若前进方向被挡，把方向投影到墙面切向。
     *
     * <p><b>为什么要记录「上次撞墙方向」：</b>在墙角处，径向与两面墙的切向会形成往复 ——
     * 这一帧切向 A 被挡、下一帧切向 B 被挡，凋灵就在夹角里来回抖。记录上次贴墙方向后，
     * 若本次两个切向都通，就优先沿用与上次同向的一侧，避免逐帧左右横跳。
     *
     * <p>副作用：通过 rememberWallDir() 写入 / 清空 brain 的 lastWallDir。
     *
     * @param brain 状态机（提供 canMove 探测与 lastWallDir 记忆）
     * @param dir 原始期望方向（水平单位向量）
     * @return 实际可用的方向；正前方可通过时原样返回 dir，真·死角（两侧都被挡）时返回 {@link Vec3#ZERO} 交由调用方随机换向
     */
    private static Vec3 slideAlongWall(WitherBrain brain, Vec3 dir) {
        double probe = WitherConfig.P1_KITE_WALL_PROBE;

        // 正前方可通过 → 不用处理，并清掉墙记忆（已脱离墙）
        if (brain.canMove(dir, probe)) {
            brain.rememberWallDir(Vec3.ZERO);
            return dir;
        }

        // 被挡：尝试左右两个切向（dir 逆时针 90° 为左，取反为右）
        Vec3 left = new Vec3(-dir.z, 0.0D, dir.x);
        Vec3 right = left.scale(-1.0D);
        Vec3 last = brain.lastWallDir();

        boolean leftOk = brain.canMove(left, probe);
        boolean rightOk = brain.canMove(right, probe);

        Vec3 chosen = null;
        if (leftOk && rightOk) {
            // 两边都通：优先选与「上次贴墙方向」同侧的一侧，避免逐帧来回跳
            if (last.lengthSqr() > 1.0E-6D && last.dot(left) > 0.0D) {
                chosen = left;
            } else if (last.lengthSqr() > 1.0E-6D && last.dot(right) > 0.0D) {
                chosen = right;
            } else {
                chosen = brain.strafeSign() > 0.0D ? left : right;
            }
        } else if (leftOk) {
            chosen = left;
        } else if (rightOk) {
            chosen = right;
        }

        if (chosen == null) {
            return Vec3.ZERO;
        }
        brain.rememberWallDir(chosen);
        return chosen;
    }
}

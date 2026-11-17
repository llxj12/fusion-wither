/*
 * 【文件职责】一阶段「平A 常态」的自主躲箭：检测到玩家射出箭就随机做一次短距离平移或升降。
 * 【所属模块】wither.behavior 行为池（与 KiteMove / BeDash 同级，全静态工具类）。
 * 【协作关系】由 PhaseOneLogic 在「无技能」分支调用；冷却、剩余 tick 与每帧步长全部存在 WitherBrain 上，
 *             本类不含实例字段，也不自己改坐标（位移统一走 brain.moveBy / brain.riseStep）。
 * 【关键约束】只在平A 常态执行：压制弹幕与 BE 走位进行中一律不躲，保证技能演出完整；
 *             躲闪期间不开火，且目标落点必须先做一次空间检查，避免把坐标驱动的凋灵塞进方块里。
 */
package com.fusionwither.wither.behavior;

import com.fusionwither.NewMod;
import com.fusionwither.wither.WitherBrain;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 一阶段 · 平A 常态：自主躲箭。
 *
 * <p><b>为什么需要它：</b>凋灵一阶段是远程风筝，玩家最自然的应对就是拉弓站桩对射。
 * 原版凋灵不会有任何闪避，等于把「站着挨箭」当成设计的一部分；这里补一个短冷却的小幅躲闪，
 * 让远程对射变成需要预判的博弈，而不是单纯比伤害。
 *
 * <p><b>为什么必须是「爆发式短位移」：</b>满蓄力弓的箭约 3 格/tick，
 * 而一阶段风筝距离只有 6.5~8.5 格 —— 箭离弦到命中只有 2~3 tick。
 * 若像风筝那样每帧挪 0.1~0.2 格，任何位移都来不及把 0.9 格宽的身体移出箭的判定。
 * 因此这里把 2.4 格在 4 tick 内走完（约 0.6 格/tick），并且一看到「箭离弦」就开始动，
 * 而不是等确认弹道会命中再动。
 *
 * <p><b>触发条件（全部满足才躲）：</b>
 * <ol>
 *   <li>冷却已就绪（触发后写 180~300 tick 随机冷却，即 9~15 秒）；</li>
 *   <li>搜索半径内存在 owner 是玩家、<b>刚射出</b>（{@code tickCount} 在反应窗口内）、
 *       且飞行方向大致指向自己的箭矢 —— 不做命中推演，擦身而过的箭也会触发；</li>
 *   <li>随机选出一个「不会把凋灵挪进方块」的候选方向（平移 / 上升 / 下降）。</li>
 * </ol>
 *
 * <p><b>不躲什么：</b>三叉戟（{@link ThrownTrident} 也继承 {@link AbstractArrow}）与凋灵自己的凋灵之首，
 * 前者速度与判定都不同、后者是本模组自己射出的弹幕，混进来会造成「躲自己的子弹」。
 */
public final class ProjectileDodge {

    /** 私有构造：全静态工具类，禁止实例化。 */
    private ProjectileDodge() {}

    /**
     * 一阶段平A 常态的躲箭主入口，每帧调用一次。
     *
     * <p><b>消耗语义：</b>返回 true 表示本帧位移与开火全部由躲闪接管，
     * 调用方（{@code PhaseOneLogic}）应立即 return，不要再执行风筝、平A 或高度修正。
     *
     * @param brain 该凋灵的状态容器（冷却、剩余 tick、步长都存在这里）
     * @return true 表示本帧处于躲闪中（含触发帧），调用方直接返回
     */
    public static boolean tick(WitherBrain brain) {
        if (!WitherConfig.P1_DODGE_ENABLED) {
            return false;
        }
        // 命中检测前先处理进行中的躲闪：已经起跳的那 4 tick 不再看新箭，
        // 否则同一发箭会连续触发多次方向选择，表现为原地抖动。
        if (brain.p1DodgeTicks() > 0) {
            Vec3 flat = brain.takeDodgeStep();
            brain.moveBy(flat, flat.length());
            double dy = brain.takeDodgeStepY();
            if (dy != 0.0D) {
                brain.riseStep(dy);
            }
            brain.tickDodge();
            return true;
        }

        if (brain.p1DodgeCd() > 0) {
            return false;
        }
        Vec3 threatDir = findThreatDirection(brain);
        if (threatDir == null) {
            return false;
        }
        if (!startDodge(brain, threatDir)) {
            // 四个候选方向都会被塞进方块：这一次放弃，但冷却照常写入，
            // 避免在狭窄地形里每帧重复扫描、重复尝试。
            brain.setP1DodgeCd(rollCooldown(brain));
            return false;
        }
        return true;
    }

    /**
     * 扫描周围的玩家箭矢，找出一发「刚刚射出」的箭，返回它的水平运动方向。
     *
     * <p><b>触发语义（本能式反应）：</b>不像上一版那样先做命中推演、确认「这一箭会命中我」才躲，
     * 而是只要在检测半径内看到玩家<b>刚把箭射出去</b>（{@code tickCount} 不超过
     * {@link WitherConfig#P1_DODGE_REACT_TICKS}），就先做一次躲闪 —— 哪怕这一箭本来会擦身而过。
     * 这样看起来是「听到弓弦响就动」，而不是「算完弹道才动」。
     *
     * <p>仍然保留一条很松的朝向过滤：箭的速度必须指向凋灵所在半空间（{@code dot > 0}）。
     * 这一条不是在做命中计算，只是避免玩家朝反方向射远处的怪时，凋灵也跟着乱跳。
     *
     * <p>副作用与取舍：箭如果在 28 格检测半径<b>之外</b>射出、之后才飞进来，此时它已经不算「刚射出」，
     * 不会触发躲闪 —— 远处冷箭本来也不该有本能反应。
     *
     * @param brain 该凋灵的状态容器
     * @return 刚射出箭的水平速度方向（未归一化）；没有新箭时返回 null
     */
    private static Vec3 findThreatDirection(WitherBrain brain) {
        WitherBoss wither = brain.wither();
        double radius = WitherConfig.P1_DODGE_DETECT_RADIUS;
        AABB search = wither.getBoundingBox().inflate(radius);
        List<AbstractArrow> arrows = wither.level().getEntitiesOfClass(AbstractArrow.class, search);

        int reactTicks = WitherConfig.P1_DODGE_REACT_TICKS;
        for (AbstractArrow arrow : arrows) {
            if (!(arrow.getOwner() instanceof Player)) {
                continue;
            }
            if (arrow instanceof ThrownTrident) {
                // 三叉戟不参与：速度更快、判定更宽，混进同一套阈值会两头都不准
                continue;
            }
            if (arrow.tickCount > reactTicks) {
                // 已经在飞的老箭：可能是上一次躲闪前射出的，再躲一次会变成一箭躲两回
                continue;
            }
            Vec3 vel = arrow.getDeltaMovement();
            if (vel.lengthSqr() < 0.01D) {
                // 落地/几乎静止，不再算「射出」
                continue;
            }
            if (vel.dot(wither.position().subtract(arrow.position())) <= 0.0D) {
                // 朝着远离我们的方向飞出去：不是射向我们，不做本能反应
                continue;
            }
            return vel;
        }
        return null;
    }

    /**
     * 从候选方向里随机挑一个可用的，写入 {@link WitherBrain#startDodge}。
     *
     * <p><b>候选构成分两种情况（实机反馈修复）：</b>
     * <ul>
     *   <li><b>距离已经近了</b>（≤ 风筝后撤阈值 {@link WitherConfig#P1_KITE_RETREAT_DISTANCE}）：
     *       躲闪的同时要「拉远距离」，所以候选改成「正后撤 + 两个后斜向 + 上升」，
     *       这一次躲完就能回到风筝带里，而不是贴着玩家原地横跳；</li>
     *   <li><b>距离不在近身区间</b>（含「距离过大」）：只做纯横移与升降，
     *       再往后退没有意义，反而会被推出战场。</li>
     * </ul>
     *
     * <p>候选顺序在每次尝试前打乱，保证各类方向机会均等。
     * 横移方向取「垂直于箭的水平速度」的左右两侧 —— 顺着箭的方向前后挪是躲不开的，
     * 只有垂直方向才能在同样时间内拉开最大的横向距离。
     *
     * @param brain     该凋灵的状态容器
     * @param threatDir 威胁箭的水平速度方向
     * @return true 表示已成功启动躲闪；全部方向都不可用时返回 false
     */
    private static boolean startDodge(WitherBrain brain, Vec3 threatDir) {
        WitherBoss wither = brain.wither();
        RandomSource random = wither.getRandom();
        double dist = WitherConfig.P1_DODGE_DISTANCE;
        int ticks = WitherConfig.P1_DODGE_DURATION_TICKS;

        Vec3 flat = new Vec3(threatDir.x, 0.0D, threatDir.z);
        Vec3 side = flat.lengthSqr() < 1.0E-4D
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : new Vec3(-flat.z, 0.0D, flat.x).normalize();
        boolean flip = random.nextBoolean();
        Vec3 sideA = flip ? side : side.scale(-1.0D);
        Vec3 sideB = flip ? side.scale(-1.0D) : side;

        Vec3[] moves = needsRetreat(brain)
                ? retreatCandidates(wither, sideA, sideB, dist)
                : normalCandidates(sideA, sideB, dist);
        shuffle(moves, random);
        for (Vec3 move : moves) {
            if (!isFreeDestination(wither, move.x, move.y, move.z)) {
                continue;
            }
            brain.startDodge(move.x, move.y, move.z, ticks, rollCooldown(brain));
            NewMod.LOGGER.debug("[凋灵/躲箭] 凋灵 {} 触发躲闪：dx={} dy={} dz={}",
                    wither.getUUID(), (float) move.x, (float) move.y, (float) move.z);
            return true;
        }
        return false;
    }

    /**
     * 这次躲闪是否要带上「后撤」分量。
     *
     * <p>判据用风筝走位的后撤阈值 {@code P1_KITE_RETREAT_DISTANCE}（默认 6.5）：
     * 低于它说明玩家已经贴脸，本次躲闪顺带拉开距离；高于它就属于「距离不算太近」，
     * 再后撤只会把自己推出固定战场 —— 也就是用户说的「除非距离过大」。
     *
     * @param brain 该凋灵的状态容器
     * @return true 表示需要在躲闪里加入后撤分量
     */
    private static boolean needsRetreat(WitherBrain brain) {
        LivingEntity target = brain.targetEntity();
        if (target == null) {
            return false;
        }
        // 必须用**水平**距离：凋灵常态悬停在离地 6 格，三维距离天然就有 6 格以上的竖直分量，
        // 用 distanceTo 会让「贴脸」永远判定不成立，后撤分支变成死代码。
        double dx = target.getX() - brain.wither().getX();
        double dz = target.getZ() - brain.wither().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal <= WitherConfig.P1_KITE_RETREAT_DISTANCE;
    }

    /**
     * 近身躲闪的候选：正后撤、两个后斜向、上升、下降。
     *
     * @param wither 目标凋灵
     * @param sideA  横移方向 A（单位向量）
     * @param sideB  横移方向 B（单位向量）
     * @param dist   总位移（格）
     * @return 候选位移数组，每个元素都是「本次躲闪总位移」向量
     */
    private static Vec3[] retreatCandidates(WitherBoss wither, Vec3 sideA, Vec3 sideB, double dist) {
        Vec3 away = awayFromTarget(wither);
        // 后斜向：后撤占 6 成、横移占 4 成 —— 既拉开距离，又保留横向脱靶的位移
        Vec3 backLeft = away.scale(0.6D).add(sideA.scale(0.4D)).normalize().scale(dist);
        Vec3 backRight = away.scale(0.6D).add(sideB.scale(0.4D)).normalize().scale(dist);
        return new Vec3[] {
                away.scale(dist),
                backLeft,
                backRight,
                new Vec3(0.0D, dist, 0.0D),
                new Vec3(0.0D, -dist, 0.0D)
        };
    }

    /**
     * 常规躲闪的候选：左右横移 + 上升 / 下降。
     *
     * @param sideA 横移方向 A（单位向量）
     * @param sideB 横移方向 B（单位向量）
     * @param dist  总位移（格）
     * @return 候选位移数组
     */
    private static Vec3[] normalCandidates(Vec3 sideA, Vec3 sideB, double dist) {
        return new Vec3[] {
                sideA.scale(dist),
                sideB.scale(dist),
                new Vec3(0.0D, dist, 0.0D),
                new Vec3(0.0D, -dist, 0.0D)
        };
    }

    /**
     * 计算「远离目标」的水平单位向量。
     *
     * <p>水平距离过小（玩家站在凋灵脚下）时方向数值不稳定，退化成一个固定轴：
     * 这种情况下无论朝哪边挪都是「离开」，没有必要追求精确的反向。
     *
     * @param wither 目标凋灵
     * @return 远离目标的水平单位向量
     */
    private static Vec3 awayFromTarget(WitherBoss wither) {
        LivingEntity target = wither.getTarget();
        if (target != null) {
            Vec3 delta = wither.position().subtract(target.position());
            Vec3 flat = new Vec3(delta.x, 0.0D, delta.z);
            if (flat.lengthSqr() > 1.0E-4D) {
                return flat.normalize();
            }
        }
        return new Vec3(1.0D, 0.0D, 0.0D);
    }

    /**
     * 原地打乱候选数组（Fisher-Yates）。
     *
     * <p>不用 {@code Collections.shuffle} 是为了走凋灵自己的随机源：
     * 每只凋灵独立随机，不会因为同帧多只凋灵而出现「齐刷刷朝同一边躲」。
     *
     * @param arr    待打乱的数组
     * @param random 随机源
     */
    private static void shuffle(Vec3[] arr, RandomSource random) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Vec3 tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }

    /**
     * 检查躲闪落点是否可用（不与方块相交）。
     *
     * <p>凋灵的位置由本模组坐标驱动，原版碰撞不会阻止它进入墙体；一旦落点被方块占据，
     * 客户端看到的就是「半截身体插在石头里」。因此这里用 {@link net.minecraft.world.level.Level#noCollision}
     * 对整段路径做一个粗检：只要终点不撞，就认为是安全的方向。
     *
     * @param wither 目标凋灵
     * @param dx     总水平位移 X
     * @param dy     总竖直位移 Y
     * @param dz     总水平位移 Z
     * @return true 表示终点与方块不冲突
     */
    private static boolean isFreeDestination(WitherBoss wither, double dx, double dy, double dz) {
        AABB target = wither.getBoundingBox().move(dx, dy, dz).deflate(0.05D);
        return wither.level().noCollision(wither, target);
    }

    /**
     * 掷一次躲箭冷却（9~15 秒，即 180~300 tick）。
     *
     * <p>区间若被配置成反的（min &gt; max），这里自动对调，避免 {@code nextInt} 抛异常。
     *
     * @param brain 该凋灵的状态容器（用凋灵的随机源，保证多只凋灵各自独立）
     * @return 冷却 tick 数
     */
    private static int rollCooldown(WitherBrain brain) {
        int min = WitherConfig.P1_DODGE_CD_MIN_TICKS;
        int max = WitherConfig.P1_DODGE_CD_MAX_TICKS;
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        if (max <= 0) {
            return 0;
        }
        return min + brain.wither().getRandom().nextInt(max - min + 1);
    }
}

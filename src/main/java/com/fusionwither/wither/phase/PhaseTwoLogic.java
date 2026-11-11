/*
 * 【文件职责】二阶段的薄层外壳：空窗期表现、凋灵骷髅补召唤节流、空窗期护甲策略。
 * 【所属模块】wither.phase 阶段逻辑。
 * 【协作关系】由 WitherBrain.tick() 在二阶段每帧调用 tick()；空窗期由
 *             WitherBrain.phaseTwoDispatch() 在「无可用行为」时反调 idleWindow()；
 *             真正的行为决策在 WitherBrain 的加权行为池里，不在本文件。
 * 【关键约束】本类**不做任何位移** —— 二阶段位移的唯一入口是
 *             WitherBrain.phaseTwoMovement()，另开一条位移路径会与"贴脸 / 压制稍远"
 *             的距离规则在同一帧互相抵消（V-01 教训：同一语义别有两处实现）。
 */
package com.fusionwither.wither.phase;

import com.fusionwither.wither.WitherBrain;
import com.fusionwither.wither.WitherConfig;
import com.fusionwither.wither.WitherSkeletonPool;
import net.minecraft.world.phys.Vec3;

/**
 * 二阶段逻辑外壳：只负责空窗期表现、骷髅补召唤与护甲续期，行为决策不在这里。
 *
 * <p><b>为什么这一层这么薄：</b>二阶段的核心是加权行为池（压制弹幕 / 冲撞），
 * 它需要跨帧共享大量状态（当前行为、行为 CD、权重随机、目标欲望值），
 * 这些状态天然属于 {@link WitherBrain}；把它们搬进本类只会制造第二份真相，
 * 从而出现状态漂移。因此这里只保留与主循环<b>时序</b>强相关的三件事：
 * 护甲续期、骷髅补召唤、空窗期标记。</p>
 *
 * <p><b>同帧调用顺序（顺序敏感）：</b>
 * {@code PhaseTwoLogic.tick} → {@code phaseTwoDispatch}（可能反调 {@code idleWindow}）。
 * 也就是说 tick() 执行的那一刻还不知道本帧会不会进空窗期，
 * 所以那里的护甲判据只能看<b>上一帧</b>的结果（{@code wasLastTickIdle}）。</p>
 */
public final class PhaseTwoLogic {

    private PhaseTwoLogic() {}

    /**
     * 二阶段每帧入口。
     *
     * <p><b>调用时机：</b>由 {@link WitherBrain#tick()} 在二阶段每帧调用，
     * 且早于 {@code phaseTwoDispatch()}（行为池派发）与 {@code phaseTwoMovement()}（位移）。</p>
     *
     * <p><b>三步，顺序不可换：</b>
     * <ol>
     *   <li>护甲续期：由 {@code ARMOR_BLOCKS_PROJECTILES} 总开关与「上一帧非空窗」共同决定；</li>
     *   <li>骷髅补召唤节流：交给 {@link WitherSkeletonPool#tickRefill}，由它内部判断「死了才补」；</li>
     *   <li>派发行为池 —— 只有这一步才可能反调 idleWindow 并改写 lastTickIdle。</li>
     * </ol></p>
     *
     * <p><b>为什么这里没有位移：</b>见文件头「关键约束」。原先这里还调用过一次距离修正，
     * 与 {@code phaseTwoMovement} 的距离修正在同一帧先后执行、互相抵消，已删除。</p>
     *
     * <p><b>副作用：</b>可能标记护甲（{@link WitherArmor#markArmored}）、可能补召唤凋灵骷髅、
     * 可能切换行为池的当前行为、可能经 {@link #idleWindow} 置位 lastTickIdle 或发射弹幕。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    public static void tick(WitherBrain brain) {
        // 护甲续期（避免被其他模组清掉）。
        //
        // V-01 修复：原先此处**无条件**标记护甲，导致 idleWindow() 里的
        // IDLE_KEEP_ARMOR 判定在同一 tick 更晚执行、且 markArmored 是幂等
        // Set.add，因此那个开关完全不起作用。
        //
        // 现在改为：非空窗期在此标记；空窗期交给 idleWindow() 按开关决定。
        // 判据用「上一帧是否空窗」—— 因为本方法在 phaseTwoDispatch() 之前执行，
        // 当时还不知道本帧会不会进空窗期。用上一帧结果可避免在 tick() 里
        // 复制一份「空窗期」定义（两处定义漂移正是 V-01 的成因）。
        //
        // 代价：从空窗期退出到行为开始的第一帧不刷新护甲。
        // 因为标记是幂等的、不会在这一帧消失，所以不产生护甲闪断，
        // 仅少了那一帧的续期。
        if (WitherConfig.ARMOR_BLOCKS_PROJECTILES && !brain.wasLastTickIdle()) {
            com.fusionwither.wither.WitherArmor.markArmored(brain.wither());
        }
        // 骷髅死了才补（节流的判据在 WitherSkeletonPool 内部，不在这里重复）
        WitherSkeletonPool.tickRefill(brain.wither(), brain.level());

        brain.phaseTwoDispatch();

        // 【验证发现的重复修复】原先这里还调用 keepDistance(brain)，
        // 而 WitherBrain.phaseTwoMovement() 也会在同一帧做距离修正 ——
        // 两条位移路径先后执行、互相抵消，且各自维护一份"期望距离"判据。
        //
        // 现在二阶段的位移**只有一个入口**：WitherBrain.phaseTwoMovement()。
        // 它同时负责距离与高度，并已包含"冲撞期间不干预"的保护。
        // 这正是 V-01 的教训：同一个语义不要有两处实现。
    }

    /**
     * 空窗期：两个行为 CD 都没转好时进入的低压段。
     *
     * <p><b>调用时机：</b>由 {@link WitherBrain#phaseTwoDispatch()} 在「没有任何可用行为」时反调。</p>
     *
     * <p><b>表现：</b>慢速游走（位移仍由 {@code phaseTwoMovement} 统一负责）+ BE 式普攻弹幕
     * （{@code brain.tickIdleAttack()}，伤害低于弹幕压制）。这是<b>刻意设计</b>的低压段，
     * 不是「什么都没做」—— 验收标准要求它「存在且可被玩家利用输出」。</p>
     *
     * <p><b>副作用：</b>把 {@code lastTickIdle} 置 true，供<b>下一帧</b>的 {@link #tick} 读；
     * 按 {@link WitherConfig#IDLE_KEEP_ARMOR} 决定是否标记护甲；可能发射一枚弹幕。
     * 注意本方法只负责<b>置位</b>，清位由 WitherBrain 在行为池真正开始一个行为的帧完成
     * （它会把 {@code lastTickIdle} 置 false）。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    public static void idleWindow(WitherBrain brain) {
        var wither = brain.wither();

        // 记录本帧为空窗期：PhaseTwoLogic.tick() 会在下一帧读它，
        // 据以决定是否跳过护甲续期（V-01 修复的判据来源）。
        brain.setLastTickIdle(true);

        // IDLE_KEEP_ARMOR 控制空窗期是否维持护甲。
        // V-01 修复后：这是二阶段护甲唯一"按开关"的标记点 ——
        // tick() 在空窗期不标记，因此关掉本开关时空窗期护甲会消失。
        if (WitherConfig.IDLE_KEEP_ARMOR) {
            com.fusionwither.wither.WitherArmor.markArmored(wither);
        }

        // 空窗期的位移由 WitherBrain.phaseTwoMovement() 统一负责
        // （它按"贴脸 / 压制稍远"的距离规则 + 离地高度移动），
        // 因此这里不再自行随机漂移 —— 那会与距离规则互相抵消。
        // （原先此处用 driftDir + P1_DRIFT_REDIRECT_* 随机换向，
        //   那些常量已随一阶段重构删除。）

        // 空窗期普攻：伤害低于弹幕压制
        brain.tickIdleAttack();
    }
}


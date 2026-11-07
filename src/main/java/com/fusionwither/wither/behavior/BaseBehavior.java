/*
 * 【文件职责】定义二阶段行为池中所有行为的抽象基类，统一「注入上下文 → 每 tick 推进 → 判定结束」的生命周期。
 * 【所属模块】wither.behavior 行为池。
 * 【协作关系】WitherBrain 构造时实例化两个子类（BarrageBehavior / CrashBehavior）；
 *             二阶段派发逻辑按权重选中一个后调 begin(this)，之后每帧调 tick()，
 *             isFinished() 为真时调度器结束它、计入 CD 并摘掉 active 引用。
 *             子类只实现 onBegin / onTick / checkFinished 三个钩子，调度骨架全在本类。
 * 【关键约束】同一时刻只允许一个行为处于 active；实例是复用的，子类必须在 onBegin 里重置自己的全部状态。
 */
package com.fusionwither.wither.behavior;

import com.fusionwither.wither.WitherBrain;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 二阶段行为池里所有行为的抽象基类，把一个「持续若干秒的战斗技能」抽象成可被每帧推进的小状态机。
 *
 * <p><b>为什么用「tick 推进 + finished 标志」而不是阻塞式脚本：</b>
 * 服务端战斗循环只有每 tick 一次的钩子（凋零的 {@code aiStep}），一个「持续 2.5~5 秒的压制弹幕」
 * 不可能由一次调用跑完；用线程或协程阻塞会脱离主线程时序、也无法每帧读取实时坐标。
 * 因此把长行为拆为 {@code onBegin(一次性初始化) + onTick(每帧推进一步) + checkFinished(自判结束)}，
 * 由 WitherBrain 每帧外部驱动 —— 行为自身不阻塞、不 sleep、不感知绝对时间。
 *
 * <p><b>生命周期：</b>由二阶段派发逻辑调用 {@link #begin(WitherBrain)} 启动（对象在 WitherBrain
 * 构造时就 new 好，是<b>复用实例</b>，不是每次新建）；随后每帧 {@link #tick()}；
 * {@link #isFinished()} 为真即本轮结束。由于实例会被反复 begin，
 * 子类必须把<b>所有</b>内部状态在 onBegin() 里重新初始化，否则会残留上一轮的进度。
 *
 * <p><b>不变量：</b>未 begin 或已 finished 时 {@link #tick()} 无任何副作用；
 * brain 未注入（null）时，本类提供的便捷访问一律走安全兜底，不会抛空指针。
 *
 * <p><b>与同包其他类的关系：</b>本类只提供调度骨架与对 WitherBrain 的只读便捷访问；
 * 具体战斗表现放子类。位移一律经 WitherBrain 的坐标所有权接口落点（登记 desired 坐标），
 * 本类与子类都不直接改实体的 {@code deltaMovement} —— 那会被原版 aiStep 覆盖。
 */
public abstract class BaseBehavior {

    /** 本行为所属的状态机，由 {@link #begin(WitherBrain)} 注入；{@link #reset()} 或尚未 begin 时为 null。 */
    private WitherBrain brain;
    /** 结束标志：{@link #tick()} 检测到 {@link #checkFinished()} 为真时置 true，begin/reset 时清为 false；为真时 tick() 直接短路。 */
    private boolean finished = false;

    /** 行为名（用于日志记录与调试命令 debugForceBehavior 指定）。 */
    public abstract String name();

    /** 行为开始的一次性初始化：实例会被复用，所有内部状态必须在此重置。由 begin() 在注入 brain 后调用。 */
    protected abstract void onBegin();

    /** 每帧推进一步。由 tick() 在「未结束」时调用；不要在此做绝对时间判断（时间由自身计数器累加）。 */
    protected abstract void onTick();

    /** 自判是否已完成。tick() 每帧在 onTick() 之后问一次，返回 true 即把 finished 置位。 */
    protected abstract boolean checkFinished();

    /** 由状态机调用，注入上下文并启动。 */
    public final void begin(WitherBrain brain) {
        this.brain = brain;
        this.finished = false;
        onBegin();
    }

    /** 由状态机调用：每帧推进一次；未注入 brain 或已结束时不做任何事。 */
    public final void tick() {
        if (brain == null || finished) {
            return;
        }
        onTick();
        if (checkFinished()) {
            finished = true;
        }
    }

    /** 本轮行为是否已结束。调度器据此上 CD 并释放 active 引用。 */
    public final boolean isFinished() {
        return finished;
    }

    /** 强制中断（调试命令用）：摘掉上下文并把结束标志复位，让实例回到「未开始」状态。 */
    public final void reset() {
        brain = null;
        finished = false;
    }

    // ---- 给子类用的便捷访问（均对 brain 为 null 做兜底）----

    /** 取回所属状态机；子类需要调用 WitherBrain 的位移/射击等接口时经此转发。 */
    protected final WitherBrain brain() {
        return brain;
    }

    /** 在 [min,max] 区间取一个随机整数；brain 为 null 时退回 min（保证技能在无上下文时也能走完）。 */
    protected final int randInt(int min, int max) {
        return brain == null ? min : brain.randint(min, max);
    }

    /** 在 [min,max] 区间取一个随机浮点数；brain 为 null 时退回 min。 */
    protected final double randDouble(double min, double max) {
        return brain == null ? min : brain.randdouble(min, max);
    }

    /** 当前目标（可为 null：目标已死 / 已失效 / 范围内什么都没有）。统一走 WitherBrain 的单一索敌入口。 */
    protected final LivingEntity target() {
        return brain == null ? null : brain.targetEntity();
    }

    /** 水平方向：从凋零指向目标的单位向量（Y=0）。无目标或水平分量退化（几乎重合）时返回 {@link Vec3#ZERO}。 */
    protected final Vec3 flatToTarget() {
        LivingEntity t = target();
        if (t == null) {
            return Vec3.ZERO;
        }
        Vec3 d = t.position().subtract(brain.wither().position());
        Vec3 flat = new Vec3(d.x, 0.0D, d.z);
        return flat.lengthSqr() < 1.0E-6D ? Vec3.ZERO : flat.normalize();
    }
}

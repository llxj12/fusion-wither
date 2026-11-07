/*
 * 【文件职责】在本帧最后一步把凋零的坐标钉回状态机登记的落点，从原版位移系统手里
 *             夺回"这一帧坐标归谁写"的所有权。
 * 【所属模块】wither.mixin 原版注入层。
 * 【协作关系】由原版 {@code aiStep}（{@code m_8107_}）每帧末尾自动触发，回调进
 *             WitherBrain.enforceMovement() → enforceNow()；上游是 WitherBrain.tick()
 *             在帧首捕获的 baseX/baseY/baseZ 与本帧陆续登记的 desired* 落点。
 * 【关键约束】必须注入 WitherBoss 自己的重写方法，注入父类同名方法对凋零实例不生效；
 *             且只能在原版写完速度之后（TAIL）执行，否则会被原版再次覆盖。
 */
package com.fusionwither.wither.mixin;

import com.fusionwither.wither.WitherBrain;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把本帧坐标的所有权从原版手里抢回来，兑现"坐标所有权"机制。
 *
 * <p><b>要修的现象：</b>放风筝走位与 BE 走位全都看不见，玩家反馈"完完全全就是原版凋零
 * 的感觉"。根因是原版位移<b>叠在</b>我们写的坐标之上：{@code LivingTickEvent} 由
 * {@code ForgeHooks.onLivingTick} 在 {@code LivingEntity.tick()} 最开头触发（字节码 0~4），
 * 所以 WitherBrain.tick() 写的坐标是全帧最早的一次写；同一帧稍后 {@code WitherBoss.aiStep}
 * （{@code m_8107_}）在偏移 214 处按"到目标的水平距离平方 &gt; 9（距离 &gt; 3 格）就朝目标加速"
 * 算出自己的 {@code deltaMovement}（加速度 0.3、衰减 0.6，终速约 0.5 格/tick），再在偏移 253
 * 调 {@code super.aiStep()} 用 {@code move()} 积分。于是我们 0.15~0.20 的后撤被原版约 0.5
 * 的接近抵消，看起来仍在靠近玩家，走位"消失"。
 *
 * <p>另外原版凋零的位移并不经过 {@code Navigation}（它直接写 {@code deltaMovement}，
 * {@code FlyingPathNavigation} 只是挂着），所以 {@code getNavigation().stop()} 拦不住它。
 * 这也是为什么"位移"必须由坐标驱动，而不是改 {@code deltaMovement}：原版 {@code aiStep}
 * 只在水平距离平方 &gt; 9 时才写一次速度，并且把 <b>y 分量硬写为 0</b>，任何"向上飞/向下坠"
 * 都不能靠原版速度系统实现。
 *
 * <p><b>为什么注入 WitherBoss 自己的 {@code m_8107_} 而不是 {@code Mob} 的同名方法：</b>
 * 凋零重写了 {@code aiStep}/{@code customServerAiStep}，注入父类方法对凋零实例不生效 ——
 * 这正是本项目早前"注入点能通过启动校验、运行时不生效"的真正原因。
 *
 * <p><b>为什么 TAIL 是安全的：</b>{@code javap -c -p} 确证 {@code WitherBoss.m_8107_()}
 * 只有一个 {@code return}，因此 TAIL 就是唯一出口，每帧恰好执行一次；钉回用的是绝对坐标
 * （见 {@link WitherBrain#enforceNow()}），天然幂等，不会叠加位移。
 */
@Mixin(WitherBoss.class)
public abstract class WitherBossMovementMixin {

    /**
     * 本帧最后一次写坐标的机会 —— 把状态机登记的落点钉回去。
     *
     * <p><b>调用时机：</b>原版 {@code aiStep} 的末尾（TAIL），即原版已经写完速度、做完
     * 位置积分之后。此时再钉坐标不会被原版覆盖，这就是"坐标所有权"能落地的原因。
     *
     * <p><b>副作用：</b>会直接把实体坐标钉到目标点、清零 {@code deltaMovement} 并清空
     * {@code fallDistance}（具体由 WitherBrain.enforceNow 执行）；本方法自身不额外做判断。
     *
     * <p><b>注入说明：</b>{@code @Inject(method = "m_8107_()V", at = @At("TAIL"))}，
     * 用 SRG 名是因为无 refmap。客户端也要跑 {@code aiStep}（用于插值），因此
     * {@code enforceMovement} 内部自带 {@code isClientSide()} 判断，这里不再重复。
     *
     * @param ci 注入回调；本注入不取消原版行为，只是在其后追加落点修正
     */
    @Inject(method = "m_8107_()V", at = @At("TAIL"))
    private void fusion$enforceOurPosition(CallbackInfo ci) {
        WitherBrain.enforceMovement((WitherBoss) (Object) this);
    }
}

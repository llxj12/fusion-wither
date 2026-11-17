/*
 * 【文件职责】出生苏醒演出的客户端渲染：让凋灵在激活动画期间「越转越快」地自转，
 *   速度在苏醒结束的瞬间达到峰值，与血条充能完成、满血、升天完全对齐。
 * 【所属模块】客户端渲染（com.fusionwither.client）。
 * 【协作关系】服务端 WitherBrain.updateAwakening 负责钉住位置与推进原版 invulnerableTicks，
 *   本类只读取这个原版同步字段计算进度，再用 RenderLivingEvent.Pre/Post 配对压栈出栈。
 * 【关键约束】必须 Dist.CLIENT 隔离注册；Pre/Post 判据必须完全一致；不新增任何同步字段或封包。
 */
package com.fusionwither.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.fusionwither.NewMod;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 出生苏醒期的<b>客户端</b>自转演出：角速度按 p³ 加速，苏醒结束时正好达到峰值。
 *
 * <p><b>为什么旋转只能在客户端做：</b>渲染器旋转整个模型用的是 {@code LivingEntity.yBodyRot}，
 * 而它不是同步字段。查 {@code LivingEntity.tick()} 字节码可知：身体朝向只在「水平速度 &gt; 0.0025」
 * 或「正在挥击」时才朝 {@code yRot} 收敛；凋灵苏醒期被钉在原地、也不挥击，
 * 所以服务端每 tick 改 {@code yRot} 只会让<b>头</b>转，<b>身体与模型不会转</b>。
 * 因此本类直接在渲染管线里给 PoseStack 叠一层绕 Y 轴旋转，不依赖任何同步朝向。
 *
 * <p><b>为什么进度用 {@code invulnerableTicks}：</b>该字段是原版 {@code SynchedEntityData}
 * （{@code DATA_ID_INV}）里的值，客户端直接读得到，且原版血条充能计时用的就是它 ——
 * 用它当进度，旋转速度峰值与「血条刚好充满、血量已满（出生时由 GameBusEvents 补满）」天然对齐。
 * 按项目约束，这里不新增任何同步字段或封包。
 *
 * <p><b>速度曲线（用户口径：三段式，最后三分之一匀速转糊）：</b>
 * <pre>
 *   段1（进度 0 → 1/3）   ：0 → peak × 2/3     线性加速（较快）
 *   段2（进度 1/3 → 2/3）：peak × 2/3 → peak   线性加速（较慢，加速度是段1 的一半）
 *   段3（进度 2/3 → 1）   ：恒定 peak           匀速
 * </pre>
 * 默认 {@code peak = 45°/tick}（每秒 2.25 圈，60 fps 下末段每帧约 15°）：
 * 能看出加速过程，末段又是明确的转糊（实机反馈两次下调 360 → 90 → 45 后的值）。
 *
 * <p><b>为什么不用幂函数：</b>p² / p³ 都是「越到结尾越快」的连续曲线，
 * 要表达「加速到某个速度后保持」就必须改成分段的速度曲线 —— 也就是现在这套：
 * 三分之一路程完成 2/3 速度、再用三分之一推到峰值、最后三分之一匀速。
 * 三段在速度上首尾相接（不跳变），只是前两段的加速度不同。
 * 峰值仍精确落在苏醒结束那一 tick，不改变苏醒时长，也不改变任何同步字段。
 *
 * <p><b>为什么在结束时不做回摆：</b>峰值正好落在苏醒最后一 tick，紧接着原版的结尾爆炸与吼声，
 * 硬停被演出盖住；苏醒结束后一阶段会由 {@code faceTarget()} 重新对齐朝向，不需要额外过渡。
 */
@Mod.EventBusSubscriber(modid = NewMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WitherSpawnSpinHandler {

    private WitherSpawnSpinHandler() {}

    /**
     * 每只正在苏醒的凋灵 → 首次观察到的 {@code invulnerableTicks}（即苏醒总时长）。
     * <p>不直接用配置里的 {@code WITHER_AWAKENING_TICKS}：客户端的配置文件与服务端可能不一致，
     * 而服务端实际开启演出时才是准的。用首次观察值归一化，两端进度曲线不会因配置差异而错位。
     * <p>用 {@link WeakHashMap}，实体被回收后条目自动消失，不会长期泄漏。
     */
    private static final Map<WitherBoss, Integer> TOTAL_TICKS = new WeakHashMap<>();

    /**
     * 渲染前压一层绕 Y 轴的旋转。
     *
     * @param event 活体渲染前事件，携带实体、{@code PoseStack} 与帧内插值 {@code partialTick}
     */
    @SubscribeEvent
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof WitherBoss wither)) {
            return;
        }
        if (!isAwakening(wither)) {
            // 苏醒已结束：清掉总时长记录，下次（新召唤的）凋灵重新从头归一化
            TOTAL_TICKS.remove(wither);
            return;
        }
        int total = TOTAL_TICKS.computeIfAbsent(wither, WitherBoss::getInvulnerableTicks);
        float angle = spinDegrees(wither, total, event.getPartialTick());
        if (angle == 0.0F) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // 只绕 Y 轴旋转：渲染器自身随后施加的「身体朝向」也是同轴旋转，两者直接叠加，
        // 不需要处理顺序问题，也不会影响位置、缩放与死亡演出的拉伸。
        pose.mulPose(Axis.YP.rotationDegrees(angle));
    }

    /**
     * 配对 {@link #onRenderPre} 的出栈。
     *
     * <p><b>判据必须与 Pre 完全一致：</b>同一帧内同步字段与 {@code partialTick} 都不变，
     * 所以 Pre 压过栈的帧 Post 必然也满足条件，不会多弹或漏弹。
     *
     * @param event 活体渲染后事件，与 Pre 一一对应
     */
    @SubscribeEvent
    public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
        if (!(event.getEntity() instanceof WitherBoss wither)) {
            return;
        }
        if (!isAwakening(wither)) {
            return;
        }
        Integer total = TOTAL_TICKS.get(wither);
        if (total == null || spinDegrees(wither, total, event.getPartialTick()) == 0.0F) {
            return;
        }
        event.getPoseStack().popPose();
    }

    /**
     * 是否处于出生苏醒期，判据与原版演出完全一致（{@code invulnerableTicks > 0}）。
     *
     * @param wither 目标凋灵
     * @return true 表示仍在原版激活动画中
     */
    private static boolean isAwakening(WitherBoss wither) {
        return wither.isAlive() && wither.getInvulnerableTicks() > 0;
    }

    /**
     * 计算当前帧应叠加的累计旋转角（度）。
     *
     * <p>插值方向：{@code invulnerableTicks} 是递减的，渲染帧位于「上一 tick 的值」与「当前值」之间，
     * 所以这一帧的剩余 tick 应取 {@code invuln + 1 - partialTick}，否则进度会比实际慢一 tick。
     *
     * @param wither      目标凋灵
     * @param total       苏醒总时长（首次观察值）
     * @param partialTick 帧内插值系数
     * @return 累计角度（度）；配置关闭或进度非法时返回 0
     */
    private static float spinDegrees(WitherBoss wither, int total, float partialTick) {
        double peak = WitherConfig.SPAWN_SPIN_PEAK_DEGREES_PER_TICK;
        if (peak <= 0.0D || total <= 0) {
            return 0.0F;
        }
        double remaining = wither.getInvulnerableTicks() + 1.0D - partialTick;
        double p = 1.0D - remaining / total;
        p = Math.max(0.0D, Math.min(1.0D, p));
        return (float) spinAngle(peak, total, p);
    }

    /**
     * 三段式速度曲线的解析积分：给定进度，返回「从苏醒开始累计转过的角度」。
     *
     * <p><b>曲线定义（实机第四轮定稿）：</b>把整段苏醒平分为三段，
     * <pre>
     *   段1（进度 0 → 1/3）：速度 0        → peak × 2/3，线性加速
     *   段2（进度 1/3 → 2/3）：速度 peak × 2/3 → peak，   线性加速
     *   段3（进度 2/3 → 1）：速度恒为 peak，匀速
     * </pre>
     * 段1 要在同样长的 1/3 时间里涨完 2/3 个峰值，段2 只涨 1/3 个峰值，
     * 因此两段加速度并不相同：<b>段1 是段2 的两倍</b> ——
     * 观感上是「起步就明显加速 → 后段放缓推向终速 → 最后 1/3 匀速转糊」。
     *
     * <p><b>为什么用解析积分而不是逐帧累加：</b>本方法只在渲染帧被调用，
     * 帧率与 tick 不同步（60 fps 下每 tick 约 3 帧），逐帧累加会让角度依赖帧率；
     * 用进度 p 直接算累计角度，任何帧率下轨迹都完全一致。
     *
     * @param peak  峰值角速度（度/tick）
     * @param total 苏醒总时长（tick）
     * @param p     已过去的苏醒进度（0 → 1）
     * @return 累计角度（度）
     */
    private static double spinAngle(double peak, int total, double p) {
        double seg = total / 3.0D;   // 每段时长：220 tick → 约 73.3 tick
        double u = p * 3.0D;         // 段坐标：0~1 段1，1~2 段2，2~3 段3
        if (u <= 1.0D) {
            // 段1：∫(2/3 · peak · u) = peak · seg · u² / 3
            return peak * seg * u * u / 3.0D;
        }
        double s = u - 1.0D;
        if (u <= 2.0D) {
            // 段2：起点角度 peak · seg / 3，∫(2/3 + s/3) = peak · seg · (2s + s²/2) / 3
            return peak * seg * (1.0D + 2.0D * s + s * s / 2.0D) / 3.0D;
        }
        // 段3：起点角度 peak · seg · 7/6，之后按 peak 匀速
        return peak * seg * (7.0D / 6.0D + (u - 2.0D));
    }
}

/*
 * 【文件职责】死亡演出的客户端渲染：在凋灵播放死亡动画期间，把它的模型逐帧拉伸变宽、拉高。
 * 【所属模块】客户端渲染（com.fusionwither.client）。
 * 【协作关系】服务端由 wither.WitherDeathSequence 驱动死亡演出并同步「发光标记 + 血量钉在 1.0」，
 *   本类只消费这两个原版自动同步的字段来判断演出状态，再用 RenderLivingEvent.Pre/Post 配对压栈出栈。
 * 【关键约束】必须用 Dist.CLIENT 隔离注册（引用的 RenderLivingEvent / PoseStack 是客户端专属类，
 *   直接放在通用类里会让专用服务器启动崩溃）；Pre/Post 的判据必须完全一致，否则会出现多弹或漏弹 pose。
 */
package com.fusionwither.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.fusionwither.NewMod;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 死亡演出的<b>客户端渲染</b>侧：让凋灵的模型在演出后段逐渐「拉伸变宽」。
 *
 * <p><b>为什么形变必须在客户端做：</b>服务端改不了模型的缩放，也发不出「把这个模型放大 1.8 倍」的指令 ——
 * 形变只存在于渲染管线里。所以这里在 {@code RenderLivingEvent.Pre} 拿到的 {@code PoseStack} 上压一层
 * {@code scale}，渲染结束在 {@code Post} 里 pop 掉；服务端那边（{@code WitherDeathSequence}）只负责
 * 把演出推进到哪一步。
 *
 * <p><b>为什么必须声明 {@code Dist.CLIENT}：</b>本类引用的 {@code RenderLivingEvent} 与 {@code PoseStack}
 * 都是客户端专属类，服务端没有它们。Forge 在专用服务器上会跳过 {@code Dist.CLIENT} 的订阅类、根本不加载本类，
 * 这就是本模组「按环境 / 总线拆类」的同一思路（见 {@code ModSetup} 与 {@code GameBusEvents} 的拆分）。
 * 本类只做渲染这一条职责，不掺任何服务端逻辑，也是为了避免被间接加载。
 *
 * <p><b>为什么用「RenderLivingEvent.Pre/Post 配对 + 原版同步字段」而不用自定义封包：</b>
 * 服务端在演出期间做了两件本来就会自动同步的事：把血量钉在
 * {@link WitherConfig#DEATH_ANIM_KEEP_HEALTH}（1.0，血量在 {@code SynchedEntityData} 里客户端直接读得到），
 * 以及打开发光标记（实体共享标志，同样自动同步，而原版发光描边是纯白色轮廓，
 * 正好就是用户要的「浑身发白光」）。既然状态已经能同步，再加一套自定义封包只会引入额外的时序与丢包风险。
 *
 * <p><b>不变量：</b>{@code DEATH_START} 里只登记「此刻确实在演出」的凋灵；同一帧内 {@code clientTicks}
 * 与同步字段都不会变，因此 Pre 与 Post 的判据结果一致，压栈与出栈严格配对。
 */
@Mod.EventBusSubscriber(modid = NewMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WitherDeathRenderHandler {

    private WitherDeathRenderHandler() {}

    /** 客户端已跑的 tick 数（只在客户端总线上推进），用作演出进度的时基。由 {@link #onClientTick} 写，被 {@link #progress} 读。 */
    private static int clientTicks = 0;

    /**
     * 每只正在播演出的凋灵 → 客户端首次观察到它的 tick。
     * <p>用 {@link WeakHashMap}：凋灵实体被回收后条目自动消失，不需要手工清理，
     * 也就不会因为长期运行累积出泄漏。
     */
    private static final Map<WitherBoss, Integer> DEATH_START = new WeakHashMap<>();

    /**
     * 推进客户端计时，作为演出进度的时基。
     *
     * <p><b>为什么只认 {@code Phase.END}：</b>客户端 tick 事件每 tick 触发 START/END 两次，
     * 只在 END 自增才能保证一 tick 恰好加一，不会让进度跑成两倍速。
     *
     * <p><b>为什么不直接用实体自身的 tickCount：</b>客户端实体是否被推进 tickCount 无法离线确证，
     * 依赖它风险太高；客户端自己是每 tick 都在跑的，用它计时最可靠。
     *
     * @param event 客户端 tick 事件，代表本模组推进计时的唯一来源
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            clientTicks++;
        }
    }

    /**
     * 渲染前压一层缩放：以实体原点（脚底）为基准把模型放大 / 拉高。
     *
     * <p><b>调用时机：</b>每只活体实体渲染前由客户端渲染管线触发；非凋灵、以及没在演出的凋灵立即返回。
     *
     * <p><b>副作用：</b>当处于形变阶段时向 {@link PoseStack} 压一层栈并写入缩放，供 {@link #onRenderPost} 弹出；
     * 同时可能在 {@code DEATH_START} 中登记这只凋灵的起始 tick。
     *
     * @param event 活体渲染前事件，携带实体、{@code PoseStack} 与帧内插值 {@code partialTick}
     */
    @SubscribeEvent
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof WitherBoss wither)) {
            return;
        }
        if (!isDying(wither)) {
            // 演出已结束（或从未开始）：清掉计时，避免这只凋灵下次再进入演出时进度不是从 0 开始
            DEATH_START.remove(wither);
            return;
        }
        int start = DEATH_START.computeIfAbsent(wither, w -> clientTicks);
        float p = progress(start, event.getPartialTick());
        if (p <= WitherConfig.DEATH_ANIM_DEFORM_START) {
            // 前半段只有白光（发光描边 + 服务端放的白粒子），不做形变 ——
            // 对应用户要求的顺序：「先发白光，然后才拉伸变宽」。
            return;
        }

        // deform 0→1 表示「形变阶段」自身从开始到结束的进度
        float span = 1.0F - WitherConfig.DEATH_ANIM_DEFORM_START;
        float deform = span <= 0.0F ? 1.0F : (p - WitherConfig.DEATH_ANIM_DEFORM_START) / span;
        float widen = 1.0F + (WitherConfig.DEATH_ANIM_WIDEN_MAX - 1.0F) * deform;
        // Y 轴用 deform²：拉伸比变宽来得更「后段」，观感上像是被越拽越长
        float stretch = 1.0F + (WitherConfig.DEATH_ANIM_STRETCH_MAX - 1.0F) * deform * deform;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.scale(widen, stretch, widen);
    }

    /**
     * 配对 {@link #onRenderPre} 的缩放压栈，在渲染结束后把它弹掉。
     *
     * <p><b>为什么判据必须与 Pre 完全一致：</b>同一帧内 {@code clientTicks} 与实体的同步字段都不会变化，
     * 因此「Pre 压了栈的帧，Post 必然也满足形变条件」，不会出现多弹（把别人的栈弹掉）或漏弹（栈越积越深、
     * 后续所有实体都被连带缩放）。
     *
     * <p><b>为什么还要再查一次 {@code DEATH_START}：</b>只有真正登记过的凋灵才可能压过栈，
     * 用登记表作为最终凭据可以避免判据漂移导致的误弹。
     *
     * @param event 活体渲染后事件，与 Pre 一一对应
     */
    @SubscribeEvent
    public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
        if (!(event.getEntity() instanceof WitherBoss wither)) {
            return;
        }
        if (!isDying(wither)) {
            return;
        }
        Integer start = DEATH_START.get(wither);
        if (start == null) {
            return;
        }
        if (progress(start, event.getPartialTick()) <= WitherConfig.DEATH_ANIM_DEFORM_START) {
            return;
        }
        event.getPoseStack().popPose();
    }

    /**
     * 判断这只凋灵此刻是否正在播放本模组的死亡演出。
     *
     * <p><b>为什么是「发光 且 血量 ≤ 2.0」：</b>单看发光会误判玩家用光谱箭让凋灵发光的情况
     * （那时血量远高于 2）；单看血量会误判普通战斗里恰好只剩 1 血的那一瞬间。
     * 两者同时成立时，几乎只可能是本模组把血量钉在 {@code DEATH_ANIM_KEEP_HEALTH} 并打开发光标记的演出状态。
     *
     * <p>两个字段都是原版自动同步的，因此这里读到的与 {@code WitherDeathSequence} 在服务端设置的
     * 是同一份状态，无需额外通信。
     *
     * @param wither 待判断的凋灵实体（客户端侧）
     * @return 正在演出返回 {@code true}
     */
    private static boolean isDying(WitherBoss wither) {
        return wither.isCurrentlyGlowing() && wither.getHealth() <= 2.0F;
    }

    /**
     * 把「起始 tick + 当前插值」换算成 0~1 的演出进度。
     *
     * <p><b>为什么用客户端自己的计时而不是猜服务端状态：</b>首次观察到演出那一帧记下起始 tick，
     * 之后按客户端累计 tick 推进；总时长与服务端共用同一个常量 {@link WitherConfig#DEATH_SEQUENCE_TICKS}，
     * 因此两边的进度天然对齐，不需要任何同步。
     *
     * @param start       客户端首次观察到该凋灵演出的 tick
     * @param partialTick 帧内插值（0.0~1.0），让进度在帧间平滑
     * @return 演出进度，已夹在 0.0~1.0；总时长配置非正时直接返回 1.0 表示已结束
     */
    private static float progress(int start, float partialTick) {
        if (WitherConfig.DEATH_SEQUENCE_TICKS <= 0) {
            return 1.0F;
        }
        float t = (clientTicks - start) + partialTick;
        float p = t / (float) WitherConfig.DEATH_SEQUENCE_TICKS;
        return p < 0.0F ? 0.0F : (p > 1.0F ? 1.0F : p);
    }
}

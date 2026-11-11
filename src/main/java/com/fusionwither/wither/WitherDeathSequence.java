/*
 * 【文件职责】凋灵的「死亡演出」状态机：把致死一击改写为"刚好压到 1.0 血"，演出期间
 *             血钉 1.0 + 全程免伤 + 发光 + 白光粒子 + 走低的哀嚎，最后按固定顺序爆炸
 *             并喂一记真实致命伤，交还原版死亡流程收场。
 * 【所属模块】wither 核心。
 * 【协作关系】伤害入口由 WitherBossHandler.onLivingDamage 调 filterDamage；
 *             每帧推进由 WitherBrain.tick() 在最前面调 tickFor() 并据此早退；
 *             状态清理由 WitherBossHandler 的死亡分支调 forget()；
 *             客户端形变（拉伸变宽）在 com.fusionwither.client.WitherDeathRenderHandler 里，
 *             它靠本类同步出去的发光标记与血量来识别"正在演出"。
 * 【关键约束】绝不拦截 LivingDeathEvent，也绝不用 kill/remove/discard 一步到位 ——
 *             那些路径都会跳过原版 die()，玩家就拿不到下界之星与经验、击杀归属也会记错。
 */
package com.fusionwither.wither;

import com.fusionwither.NewMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 基岩版式死亡演出状态机：<b>浑身发白光 → 拉伸变宽 → 爆炸死亡</b>。
 *
 * <p><b>为什么要自己写这一套：</b>这三段在 Java 版原版里<b>不存在</b> ——
 * 原版凋灵血量归零就是普通死亡（尸体变红倒地 + 掉落），既没有白光也没有形变。
 * 因此整段演出必须自建状态机，把"真正的死亡"推迟到演出结束之后。</p>
 *
 * <p><b>【最关键的架构决定：不能只取消死亡事件】</b>
 * 最初的想法是"取消 {@code LivingDeathEvent}，让凋灵多活几秒演完动画"。
 * 这是错的，原因是该事件触发时血量<b>已经是 0</b>：
 * <ul>
 *   <li>血量 0 意味着 {@code isDeadOrDying()} 成立，下一 tick 原版照样走
 *       {@code tickDeath()} —— 尸体会照常消失，动画也就没了；</li>
 *   <li>而 {@code die()} <b>根本没有被执行</b> → 原版的下界之星与经验
 *       <b>全部丢失</b>，玩家打完 BOSS 拿不到战利品，击杀归属也是错的。</li>
 * </ul>
 * 同理也不能用 {@code discard()} / {@code remove()} 一步到位 —— 一样跳过
 * {@code die()} 的战利品逻辑。</p>
 *
 * <p><b>所以改为在"伤害"阶段就夺取死亡权（本类的三段骨架）：</b>
 * <ol>
 *   <li><b>夺取死亡权</b>（{@link #filterDamage}）：在伤害事件
 *       {@code LivingDamageEvent} 里，把致死的那一击的 {@code setAmount}
 *       改成"<b>刚好把血量压到 1.0</b>"而不是压到 0。原版拿到的是一个小额伤害，
 *       于是 {@code die()} 不会被调用，凋灵"活着但只剩一丝血" ——
 *       这就是"死亡权被我们拿走"的含义。</li>
 *   <li><b>演出中</b>（{@link #tick}）：每 tick 把血量钉在
 *       {@link WitherConfig#DEATH_ANIM_KEEP_HEALTH}（1.0）、重设发光标记、放白色粒子、
 *       按进度放走低的哀嚎；同时把<b>一切伤害</b>取消（连 {@code genericKill} / 虚空这类
 *       "跳过无敌判定"的来源也一并取消 —— 只靠 {@code setInvulnerable(true)}
 *       是拦不住它们的）。位置不用处理，由 WitherBrain 的坐标所有权机制钉住。</li>
 *   <li><b>收尾</b>（{@link #finish}）：关掉发光与无敌、清无敌帧，先炸一炮，
 *       再用一次<b>真实致命伤</b>（{@code hurt}）让原版走完
 *       {@code die()} —— 掉落、击杀归属、死亡音效全部由原版完成。</li>
 * </ol></p>
 *
 * <p><b>为什么用 {@code LivingDamageEvent} 而不是 {@code LivingHurtEvent}：</b>
 * 两者都在 {@code LivingEntity.m_6475_}（actuallyHurt）内部触发，但
 * {@code LivingHurtEvent} 早于护甲/吸收减免（拿到的不是最终伤害），
 * 而 {@code LivingDamageEvent} 拿到的才是"真正要从血量里减掉的那个值"
 * （紧接在 {@code setHealth(getHealth() - amount)} 之前）。
 * 因此 {@code amount >= getHealth()} 就是"这一击会打死它"的<b>精确</b>判据，
 * 不会把"被护甲挡下来的一击"误判成致死。</p>
 *
 * <p><b>为什么拉伸变宽不在这个类里：</b>形变是<b>客户端渲染</b>的事
 * （服务端改不了模型的缩放），实现在
 * {@code com.fusionwither.client.WitherDeathRenderHandler}。服务端只负责把
 * "是否正在演出"这件事同步出去 —— 用的都是原版现成的同步字段：
 * 血量（{@code SynchedEntityData}）与发光标记（实体共享标志），
 * 因此不需要任何自定义封包，也不需要给凋灵加新的 DataAccessor。</p>
 */
public final class WitherDeathSequence {

    /** 演出阶段。状态机只有三态，且只能单向推进（IDLE → PLAYING → ALLOW_DEATH）。 */
    public enum Stage {
        /** 未开始（该凋灵的默认态）。 */
        IDLE,
        /** 演出进行中：血量钉住、全程免伤、发光、放白光粒子。 */
        PLAYING,
        /** 已收尾、死亡已放行：本阶段不再做任何事，交由原版死亡流程收场。 */
        ALLOW_DEATH
    }

    /** 已开始的凋灵 → 演出状态。与 {@link WitherBrain} 一样按 UUID 索引。
     *  <p>用 UUID 而不是实体引用，是为了不让这张表持有实体、阻碍它被回收。</p>*/
    private static final Map<UUID, WitherDeathSequence> ACTIVE = new HashMap<>();

    /** 本次演出属于哪只凋灵。 */
    private final WitherBoss wither;
    /** 构造时抓一份 Level，避免每 tick 取一次。 */
    private final Level level;

    /** 状态机当前位置。由 {@link #begin} 置 PLAYING、{@link #finish} 置 ALLOW_DEATH，
     *  除此之外没有别的写入点（保证单向推进）。 */
    private Stage stage = Stage.IDLE;
    /** 演出剩余 tick。初值 {@code DEATH_SEQUENCE_TICKS}，每帧减 1，减到 0 触发 {@link #finish}。 */
    private int ticksLeft;
    /** 本次演出已过 tick（用于粒子/音效的间隔取模与进度计算）。 */
    private int elapsed;
    /** 触发演出的那一击的伤害来源 —— 收尾时优先用它造成致命伤，
     *  这样原版会把击杀归属记给打出这一击的玩家（{@code /kill} 用的
     *  {@code genericKill} 是没有归属的）。 */
    private DamageSource pendingSource;

    /**
     * 私有构造，只能由 {@link #filterDamage} 在"致死一击"发生的瞬间创建。
     *
     * <p><b>为什么私有 + 只在 filterDamage 里 new：</b>演出实例与凋灵一一对应，
     * 挂在 {@link #ACTIVE} 表上；入口收成一条保证"同一只凋灵只会有一个实例"，
     * 避免多入口各自 new 出两份状态机。</p>
     *
     * <p><b>为什么构造时就缓存 level：</b>演出期间每 tick 都要往 level 发粒子与音效，
     * 缓存一次比每帧调 {@code wither.level()} 更省，也避开运行期取到别的实例的风险。</p>
     *
     * @param wither 即将进入演出的凋灵
     */
    private WitherDeathSequence(WitherBoss wither) {
        this.wither = wither;
        this.level = wither.level();
    }

    // ===============================================================
    //  一、伤害钩子（由 WitherBossHandler.onLivingDamage 调用）
    // ===============================================================

    /**
     * 伤害过滤入口：判定这一击是否致死，并接管/取消伤害。
     *
     * <p><b>三件事按顺序发生：</b>
     * <ol>
     *   <li>还没开始演出、且这一击<b>不致死</b> → 原样放行（不影响正常战斗）；</li>
     *   <li>还没开始演出、且这一击<b>致死</b> → 创建演出实例并返回"刚好把血量
     *       压到 {@link WitherConfig#DEATH_ANIM_KEEP_HEALTH}"的量，夺走死亡权；</li>
     *   <li>演出进行中 → 返回 0，把一切伤害（含跳过无敌判定的来源）整个取消。</li>
     * </ol></p>
     *
     * <p><b>调用时机：</b>{@code LivingDamageEvent} 内，紧接在
     * {@code LivingEntity.actuallyHurt} 准备从血量里扣 {@code amount} 之前。
     * 之所以用这个事件而不是 {@code LivingHurtEvent} 见类注释。</p>
     *
     * <p><b>副作用：</b>可能创建演出实例并写入 {@link #ACTIVE}；
     * 会被动地两侧钳制血量（返回的伤害量决定最终血量）。</p>
     *
     * @param wither 受击的凋灵
     * @param source 伤害来源；致死时会记录在 {@link #pendingSource}，供收尾时复用
     * @param amount 本次伤害（{@code LivingDamageEvent.getAmount()}，
     *               已是扣完护甲与吸收、真正要减掉的值）
     * @return 实际应当施加的伤害量（0 = 完全免伤）
     */
    public static float filterDamage(WitherBoss wither, DamageSource source, float amount) {
        if (!WitherConfig.DEATH_SEQUENCE_ENABLED) {
            return amount;
        }
        if (wither.level().isClientSide()) {
            return amount;
        }
        WitherDeathSequence seq = ACTIVE.get(wither.getUUID());
        if (seq == null) {
            // 血量 <= 0 时不该再开演出（例如别的路径已经把它打死了）
            if (amount < wither.getHealth() || wither.getHealth() <= 0.0F) {
                return amount;
            }
            seq = new WitherDeathSequence(wither);
            ACTIVE.put(wither.getUUID(), seq);
            return seq.begin(source);
        }
        return seq.continueFilter(amount);
    }

    /**
     * 开始演出，并返回"夺取死亡权"之后实际应当造成的伤害量。
     *
     * <p><b>为什么致死判据单独抽在这里：</b>把"这一击会打死它"的判定与其余通用过滤分开，
     * 免得夺权逻辑被后面的免伤分支混在一起看不清（{@code filterDamage} 里
     * {@code amount >= getHealth()} 的分支就对应本方法）。</p>
     *
     * <p><b>副作用：</b>记录 {@code pendingSource}、把状态推进到 PLAYING、
     * 初始化 {@code ticksLeft}/{@code elapsed}、置发光与无敌、写诊断日志、
     * 放一次低音调的开场哀嚎（{@code WITHER_DEATH}）。</p>
     *
     * @param source 致死那一击的来源，收尾时会优先复用它
     * @return 刚好把血量压到 {@code DEATH_ANIM_KEEP_HEALTH} 的伤害量（下限 0）
     */
    private float begin(DamageSource source) {
        this.pendingSource = source;
        this.stage = Stage.PLAYING;
        this.ticksLeft = WitherConfig.DEATH_SEQUENCE_TICKS;
        this.elapsed = 0;

        // 发光标记：它是"浑身发白光"的<b>主体</b>（原版发光描边是纯白轮廓），
        // 同时也是客户端识别演出开始的同步信号（实体共享标志，自动同步）。
        wither.setGlowingTag(true);
        // 无敌标志是第二道闸门：普通伤害在 hurt() 开头就被 isInvulnerableTo 拦掉。
        // 但注意它拦不住 genericKill / 虚空这类 BYPASSES_INVULNERABILITY 来源，
        // 那些由上面的 filterDamage 统一取消。
        wither.setInvulnerable(true);

        NewMod.LOGGER.info("[凋灵/诊断] 凋灵 {} 血量见底，进入死亡演出：{} tick —— 白光 + 拉伸变宽 + 持续哀嚎 + 爆炸，期间免除一切伤害",
                wither.getUUID(), WitherConfig.DEATH_SEQUENCE_TICKS);

        // 持续音效的"起手"：低音调地放一次原版死亡音，把演出的开场定住。
        // 注意它<b>不是</b>收尾那一声 —— 收尾由原版 die() 自己放（见类注释）。
        playSound(SoundEvents.WITHER_DEATH,
                WitherConfig.DEATH_SOUND_OPEN_VOLUME, WitherConfig.DEATH_SOUND_OPEN_PITCH);

        // 把这一击的伤害改成"刚好吃掉剩余血量到 1.0"，而不是到 0。
        // 血量一旦为 0，isDeadOrDying() 就成立，原版下一 tick 会走死亡路径。
        return Math.max(0.0F, wither.getHealth() - WitherConfig.DEATH_ANIM_KEEP_HEALTH);
    }

    /**
     * 演出开始之后的后续伤害过滤。
     *
     * <p><b>PLAYING 期间一律返回 0：</b>连 {@code genericKill}、虚空这类跳过无敌判定的
     * 来源也一并取消。这是刻意的 —— 演出只有 4 秒（{@code DEATH_SEQUENCE_TICKS} = 80），
     * 期间"打不动"才符合"BOSS 正在倒下"的观感。</p>
     *
     * <p><b>ALLOW_DEATH 期间原样放行：</b>这正是 {@link #finish} 自己触发的那一次
     * 真实致命伤，必须放行。也正因如此，{@link #finish} 必须<b>先</b>把状态改到
     * ALLOW_DEATH、<b>再</b>调用 {@code hurt}，否则那一击会被本方法当成演出期伤害取消掉，
     * 结果就是永远死不了。</p>
     *
     * @param amount 原样返回给原版时使用的伤害量
     * @return 应当施加的伤害量（PLAYING 返回 0）
     */
    private float continueFilter(float amount) {
        if (stage == Stage.PLAYING) {
            // 演出期间免伤：包括 genericKill、虚空这类跳过无敌判定的来源。
            // 这是刻意的 —— 演出只有 4 秒，期间"打不动"才符合"BOSS 正在倒下"的观感。
            return 0.0F;
        }
        // ALLOW_DEATH：这正是我方 finish() 自己触发的那一次真实致命伤，必须放行。
        return amount;
    }

    // ===============================================================
    //  二、每 tick 推进（由 WitherBrain.tick() 调用）
    // ===============================================================

    /**
     * 演出期间每帧推进的入口。
     *
     * <p><b>调用时机：</b>{@link WitherBrain#tick()} 在其<b>所有</b> update* 与技能派发
     * <b>之前</b>调用本方法，并据返回值立即早退。因此演出期间：</p>
     * <ul>
     *   <li>不走阶段判定、不触发技能、不射击、不回血 —— 与出生苏醒（激活动画）同构；</li>
     *   <li>位置由 WitherBrain 的"本帧坐标所有权"机制钉住
     *       （本帧不产生位移意图，{@code aiStep} 末尾会被钉回本帧起点），
     *       所以这里不需要也不应该再动坐标。</li>
     * </ul>
     *
     * <p><b>副作用：</b>推进/结束演出（可能经 {@link #finish} 生成爆炸、造成致命伤、清表项）。</p>
     *
     * @param wither 待检查的凋灵
     * @return true = 本帧处于死亡演出（或已放行死亡）中，调用方应立即返回
     */
    public static boolean tickFor(WitherBoss wither) {
        WitherDeathSequence seq = ACTIVE.get(wither.getUUID());
        if (seq == null) {
            return false;
        }
        seq.tick();
        return true;
    }

    /**
     * 推进一帧演出：钉血量 → 重设发光 → 放粒子与哀嚎 → 到点收尾。
     *
     * <p><b>为什么 ALLOW_DEATH 直接返回：</b>那一刻起"死亡"已放行、致命伤已喂出去，
     * 本实例不再需要做任何事；但它仍留在 {@link #ACTIVE} 表里，好让
     * {@link #tickFor} 继续对调用方返回 true（继续早退），
     * 直到 WitherBossHandler 的死亡分支统一清理（与 {@link WitherBrain} 的生命周期一致）。</p>
     *
     * <p><b>为什么每帧都要 {@code setHealth}：</b>血量是演出的"锚"——它必须始终停在 1.0，
     * 既不能被回血抬上去（那会让凋灵看起来没在倒下），也绝不能漂到 0
     * （那会让 {@code isDeadOrDying()} 成立、原版提前走死亡路径）。</p>
     *
     * <p><b>副作用：</b>写血量、发光标记、{@code elapsed}/{@code ticksLeft}；
     * 可能生成粒子与音效；{@code ticksLeft} 归零时调用 {@link #finish}。</p>
     */
    private void tick() {
        // 已放行死亡：什么都不做，只继续把状态机钉在早退上，
        // 直到 WitherBossHandler 的死亡分支统一清理（与 WitherBrain 生命周期一致）。
        if (stage != Stage.PLAYING) {
            return;
        }
        elapsed++;
        ticksLeft--;

        // 钉血量：保证它绝不会自己漂到 0（回血/伤害都已在这条路径之外）。
        wither.setHealth(WitherConfig.DEATH_ANIM_KEEP_HEALTH);
        // 发光每帧重设一次（幂等；实体共享标志只在变化时才标脏）。
        wither.setGlowingTag(true);

        emitParticles();
        emitDeathSound();

        if (ticksLeft <= 0) {
            finish();
        }
    }

    /** 当前演出进度，取值 0~1。
     *  <p>用 {@code elapsed / DEATH_SEQUENCE_TICKS} 计算，并显式钳到 [0,1]，
     *  这样即使总时长配成 0 或极小值也不会出现除零或越界的进度。</p>
     *  <p>为 0 时长单独兜底返回 1.0：表示"一开场就是尾声"，
     *  让音调与粒子直接落到终值，而不是停在起点。</p>*/
    private float progress() {
        if (WitherConfig.DEATH_SEQUENCE_TICKS <= 0) {
            return 1.0F;
        }
        float p = (float) elapsed / (float) WitherConfig.DEATH_SEQUENCE_TICKS;
        return p < 0.0F ? 0.0F : (p > 1.0F ? 1.0F : p);
    }

    /**
     * 生成白色粒子：用 {@code END_ROD}（原版白色发光点）从身体向外飘，
     * 越接近爆炸越浓；再叠少量 {@code CRIT} 制造"被点亮"的爆闪感。
     *
     * <p><b>为什么粒子数随进度增长：</b>基准量乘 {@code 0.5 + progress}，
     * 于是开头约半量、结尾约 1.5 倍 —— 白光"越聚越亮"直到爆炸，是这段演出的节奏骨架。</p>
     *
     * <p><b>只在服务端生成：</b>{@code ServerLevel.sendParticles} 会把粒子广播给追踪该区块的
     * 玩家，客户端不需要写任何生成逻辑；因此这里用 {@code instanceof ServerLevel}
     * 模式匹配筛掉客户端，避免在客户端世界里空跑。</p>
     *
     * <p><b>副作用：</b>按间隔（{@code DEATH_PARTICLE_INTERVAL_TICKS}）在服务端广播两组粒子；
     * 间隔配 0 表示每帧都出。</p>
     */
    private void emitParticles() {
        if (WitherConfig.DEATH_PARTICLE_INTERVAL_TICKS > 0
                && elapsed % WitherConfig.DEATH_PARTICLE_INTERVAL_TICKS != 0) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        float p = progress();
        double cx = wither.getX();
        // 眼睛高度附近：既是发光描边的视觉中心，也是原版五处发射点所在的高度带
        double cy = wither.getY() + 1.5D;
        double cz = wither.getZ();
        int n = (int) Math.ceil(WitherConfig.DEATH_PARTICLE_COUNT * (0.5F + p));
        serverLevel.sendParticles(ParticleTypes.END_ROD, cx, cy, cz,
                n, 0.9D, 1.1D, 0.9D, 0.03D);
        serverLevel.sendParticles(ParticleTypes.CRIT, cx, cy, cz,
                Math.max(1, n / 2), 0.8D, 1.0D, 0.8D, 0.05D);
    }

    /**
     * 死亡演出的<b>持续性</b>音效 —— 模拟 BE 版那声一路走低的哀嚎。
     *
     * <p><b>为什么要"按间隔重发"而不是做一个真·循环音源：</b>
     * 单次 {@code wither_ambient} 本身有长度，重发间隔（
     * {@code DEATH_SOUND_INTERVAL_TICKS} = 20 tick）取得略小于它时听感就是连续的，
     * 因此不需要自己写客户端 {@code SoundInstance}（那还要再开一个
     * {@code Dist.CLIENT} 类来隔离，成本远高于收益）。</p>
     *
     * <p><b>音调如何下沉：</b>{@code pitch = START + (END - START) * progress}，
     * 即随进度从 {@code DEATH_SOUND_PITCH_START}（1.0）<b>线性</b>降到
     * {@code DEATH_SOUND_PITCH_END}（0.5）—— 整段听下来是"越鸣越沉"，
     * 最后接上爆炸。这正是 BE 版凋灵死亡音的形态，也是 Java 版原版那一声
     * 短促收场音衬托不出来的部分。</p>
     *
     * <p><b>副作用：</b>按间隔（{@code elapsed % 20 == 0}，即开场后第 20/40/60 帧）
     * 广播一次 {@code WITHER_AMBIENT}；音量配 0 表示整段关闭。</p>
     */
    private void emitDeathSound() {
        if (WitherConfig.DEATH_SOUND_VOLUME <= 0.0F) {
            return;
        }
        if (WitherConfig.DEATH_SOUND_INTERVAL_TICKS > 0
                && elapsed % WitherConfig.DEATH_SOUND_INTERVAL_TICKS != 0) {
            return;
        }
        float pitch = WitherConfig.DEATH_SOUND_PITCH_START
                + (WitherConfig.DEATH_SOUND_PITCH_END - WitherConfig.DEATH_SOUND_PITCH_START)
                * progress();
        playSound(SoundEvents.WITHER_AMBIENT, WitherConfig.DEATH_SOUND_VOLUME, pitch);
    }

    /**
     * 在全场广播一次音效。
     *
     * <p><b>第一个参数传 {@code null} = 广播给所有能听到该位置的玩家</b>，
     * 与本项目 {@code VisualFeedback} 里既有的音效广播是同一套写法。</p>
     *
     * <p><b>为什么音源取 {@code HOSTILE}：</b>凋灵是敌对生物，音量衰减按该声道计算；
     * 若用 {@code MASTER} 会被玩家的"敌对生物音量"设置忽略，音量与其它怪音不成比例。</p>
     *
     * <p><b>音高取 {@code getY() + 1.0}：</b>落在身体中段，而不是脚底 ——
     * 听感上音源在"身体里"而不是"脚下贴地"。</p>
     *
     * @param sound  要播放的音效
     * @param volume 音量（1.0 = 标准）
     * @param pitch  音调（1.0 = 原调；越小越"沉"）
     */
    private void playSound(SoundEvent sound, float volume, float pitch) {
        level.playSound(null, wither.getX(), wither.getY() + 1.0D, wither.getZ(),
                sound, SoundSource.HOSTILE, volume, pitch);
    }

    // ===============================================================
    //  三、收尾：爆炸 + 真实死亡
    // ===============================================================

    /**
     * 演出结束的收尾。步骤顺序是有讲究的，不能调换：
     * <ol>
     *   <li><b>关发光</b>（{@code setGlowingTag(false)}）：否则尸体会一直挂着白色描边
     *       （发光是持久标记），客户端也会一直按"演出中"继续形变；</li>
     *   <li><b>临时无敌 + 炸一炮</b>：先炸是为了"爆炸就是死亡那一刻"的观感，
     *       但必须让凋灵自己不受这一炸 —— 否则它会被自己的爆炸炸死，
     *       那一击的击杀归属会记成"凋灵自己"，玩家打空血条却拿不到归属与经验；</li>
     *   <li><b>解除无敌并清零 {@code invulnerableTime}</b>：原版在
     *       {@code invulnerableTime > 10} 时会直接丢弃伤害，而下面这一击是<b>唯一一次</b>
     *       真正的致命伤、必须落地，所以显式清零（解除无敌则是因为第 2 步刚把它置为 true）；</li>
     *   <li><b>先把状态改到 ALLOW_DEATH，再喂真实致命伤</b>：只有走 {@code hurt()}
     *       才会到原版 {@code die()}（{@code kill()} / {@code remove()} / {@code discard()}
     *       都会跳过掉落与击杀归属）。而 {@code hurt()} 会重新进入伤害过滤链，
     *       被 {@link #continueFilter} 看到 —— 若那时状态还是 PLAYING，这一击会被自己取消，
     *       结果永远死不了。所以改状态必须在 {@code hurt} 之前。</li>
     * </ol>
     *
     * <p><b>副作用：</b>生成一次爆炸（经 {@code CrashEffects.bigExplosion}，受 F-09 保护名单约束）、
     * 关闭发光、解除无敌、清零无敌帧、把状态置为 ALLOW_DEATH、对凋灵造成一次真实致命伤、
     * 写一行诊断日志。</p>
     */
    private void finish() {
        wither.setGlowingTag(false);

        // ---- 第一段：爆炸（基岩版式死亡爆炸）----
        // 走 CrashEffects.bigExplosion → Level.explode，
        // 因此 WitherBossHandler.onExplosionDetonate 会按"爆炸源实体是凋灵"
        // 把 F-09 保护名单（箱子/床/工作站点等）自动套上去，
        // 与冲撞、二阶段入场爆炸是同一道闸门。
        wither.setInvulnerable(true);
        CrashEffects.bigExplosion(wither, level,
                WitherConfig.DEATH_EXPLOSION_POWER,
                WitherConfig.DEATH_EXPLOSION_BREAK_BLOCKS);

        // ---- 第二段：真实死亡 ----
        wither.setInvulnerable(false);
        // 原版在 invulnerableTime > 10 时会丢弃伤害；这一击是唯一一次真正的
        // 致命伤，必须让它落地，因此显式清零。
        wither.invulnerableTime = 0;
        stage = Stage.ALLOW_DEATH;

        DamageSource src = (pendingSource != null)
                ? pendingSource
                : wither.damageSources().genericKill();
        wither.hurt(src, Float.MAX_VALUE);
        if (!wither.isDeadOrDying()) {
            // 记录下的那一击被原版判定免疫时（例如它是"凋灵之首"——
            // WitherBoss.hurt 对"来源实体是凋灵"的伤害一律免疫），
            // 退回到原版 /kill 用的 genericKill：它带 BYPASSES_INVULNERABILITY，
            // 一定能落地。
            wither.hurt(wither.damageSources().genericKill(), Float.MAX_VALUE);
        }

        NewMod.LOGGER.info("[凋灵/诊断] 凋灵 {} 死亡演出结束：爆炸威力 {}，已放行原版死亡（掉落与击杀归属由原版 die() 完成，实际死亡={}）",
                wither.getUUID(), WitherConfig.DEATH_EXPLOSION_POWER, wither.isDeadOrDying());
    }

    // ===============================================================
    //  四、生命周期与调试
    // ===============================================================

    /** 清理状态（由 {@code WitherBossHandler.tick()} 的死亡分支调用）。
     *  <p>只从 {@link #ACTIVE} 摘掉本实例，不做任何收尾动作 ——
     *  收尾（爆炸/致命伤）在 {@link #finish} 已经完成，这里只是让
     *  {@link #tickFor} 停止对调用方返回 true。</p>*/
    public static void forget(WitherBoss wither) {
        ACTIVE.remove(wither.getUUID());
    }

    /** 调试命令用的一行描述（{@code /wboss dump} 与 {@code /wboss diag}）。
     *  <p>IDLE 与"表里没有记录"都返回"无"，对使用者而言两者同义（没有演出在进行）。</p>
     *  @param wither 待查询的凋灵
     *  @return 人类可读的演出状态；PLAYING 时附上剩余 tick 数 */
    public static String describe(WitherBoss wither) {
        WitherDeathSequence seq = ACTIVE.get(wither.getUUID());
        if (seq == null || seq.stage == Stage.IDLE) {
            return "无";
        }
        return switch (seq.stage) {
            case PLAYING -> String.format("进行中(剩余 %d tick)", seq.ticksLeft);
            case ALLOW_DEATH -> "已放行死亡";
            case IDLE -> "无";
        };
    }

    /** 该凋灵此刻是否正在播死亡演出（供其它模块查询，例如渲染与诊断）。
     *  <p>只有 PLAYING 才算"正在演出" —— ALLOW_DEATH 之后死亡已放行，
     *  查询方（例如客户端形变判断）应当停手，交由死亡动画接管。</p>*/
    public static boolean isPlaying(WitherBoss wither) {
        WitherDeathSequence seq = ACTIVE.get(wither.getUUID());
        return seq != null && seq.stage == Stage.PLAYING;
    }
}

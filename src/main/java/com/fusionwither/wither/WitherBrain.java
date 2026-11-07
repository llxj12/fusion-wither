/*
 * 【文件职责】凋零 Boss 的行为状态机：每帧推进阶段切换、索敌、射击冷却扣减、行为池调度，并登记本帧希望到达的坐标。
 * 【所属模块】wither 核心（com.fusionwither.wither），是凋零所有战斗逻辑的中枢。
 * 【协作关系】外部每 tick 调 tick()；tick() 内部向 PhaseOneLogic / PhaseTwoLogic 派发阶段逻辑，
 * 向 BarrageBehavior / CrashBehavior 派发二阶段行为；本类登记 desired 坐标后，由
 * WitherBossMovementMixin 注入原版 WitherBoss.aiStep(m_8107_) 末尾回调 enforceMovement() 真正落地。
 * 【关键约束】本类一律不靠写 deltaMovement 位移：原版 aiStep 仅在水平距离平方>9 时写一次速度且 y 分量硬写为 0，
 * 因此上下左右的一切移动都必须走「登记 desired 坐标 → mixin 钉回」的坐标所有权机制。
 */
package com.fusionwither.wither;

import com.fusionwither.NewMod;
import com.fusionwither.wither.behavior.BaseBehavior;
import com.fusionwither.wither.behavior.BarrageBehavior;
import com.fusionwither.wither.behavior.CrashBehavior;
import com.fusionwither.wither.phase.PhaseOneLogic;
import com.fusionwither.wither.phase.PhaseTwoLogic;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 凋零（WitherBoss）的行为总控类：一个凋零实体对应一个实例，随凋零存活而存在。
 *
 * <p>设计要点：
 * <ul>
 *   <li>凋零实体本身只是「躯壳」，所有决策（阶段、索敌、冷却、行为、位移意图）都收拢在本类，
 *       避免逻辑散落在 mixin 与实体子类里；实体侧只保留注入点。</li>
 *   <li>位置不是靠速度驱动的：tick() 开头锁定当前坐标 base*，之后各逻辑用 desired* 表达「本帧想去哪」；
 *       若本帧没人登记 desired，mixin 就把凋零钉回 base*，等价于「冻结」。</li>
 *   <li>本类不自己扫描怪物来决定打谁，而是消费原版 getTarget()，因此「玩家优先」由原版 targetSelector 天然保证。</li>
 * </ul>
 *
 * <p>生命周期：由 {@link #of(WitherBoss)} 在需要时惰性创建并登记进 {@link #BRAINS}；
 * 凋零死亡或移除时由 {@link #forget(WitherBoss)} 注销，防止 map 无界增长。
 *
 * <p>不变量：
 * <ul>
 *   <li>禁止写 deltaMovement 来位移（原因见文件头）；</li>
 *   <li>每个实例只服务唯一一个凋零实体，UUID 是 map 的唯一键；</li>
 *   <li>坐标所有权：本帧登记了 desired 就钉 desired，否则钉回 base*，两者必居其一。</li>
 * </ul>
 */
public final class WitherBrain {

    /** UUID → 行为脑 的全局登记表：mixin 侧只能拿到实体，靠它反查出对应的 WitherBrain 来执行坐标钉回。 */
    private static final Map<UUID, WitherBrain> BRAINS = new HashMap<>();

    /** 本脑所服务的凋零实体；构造后不变，所有读写坐标/血量/朝向的调用都以它为入口。 */
    private final WitherBoss wither;

    /** 凋零所在维度，构造时缓存一次，供射线检测、方块高度查询与实体扫描使用。 */
    private final Level level;
    /** 本脑独占的随机源：行为池加权抽取、冷却抖动、随机水平方向与粒子时机都由它产生。 */
    private final Random random = new Random();

    /** 阶段标志：true 表示已进入二阶段（压制到地面附近 + 行为池调度）。由 enterPhaseTwo() 置位、updatePhase() 可回退。 */
    private boolean phase2 = false;

    /** 「是否已经历过二阶段入场」的一次性闸门：置位后 enterPhaseTwo() 只重复刷新 phase2，不再触发坠落/爆炸/召唤。 */
    private boolean phaseTwoEntered = false;
    /** 欲望值：1.0 表示「高欲望」（目标血量 ≤ DESIRE_PLAYER_HP_THRESHOLD），0.0 表示普通；由 updateDesire() 写入，驱动回血速率、狂暴模式与行为权重。 */
    private double desire = 0.0D;
    /** desire 切换后的防抖倒计时，单位 tick：未归零前 updateDesire() 不再改写 desire，避免目标血量在阈值附近反复抖动。 */
    private int desireCooldown = 0;
    /** 入场定格剩余时长，单位 tick：>0 期间凋零原地不动只转头，由 landPhaseTwoEntry()/debug 写入，handleIntro() 每帧递减。 */
    private int introTicks = 0;

    /** 出生苏醒日志去重标志：保证「进入苏醒」与「苏醒结束」两条日志各自只打一次，避免每帧刷屏。 */
    private boolean awakeningAnnounced = false;

    /** 二阶段当前正在执行的行为，null 表示空闲；由 phaseTwoDispatch() 写入，stopAllBehaviors() 清空。 */
    private BaseBehavior active = null;
    /** 压制弹幕行为实例，与 crash 一样在构造时创建一次、全程复用（行为本身无可变字段，状态留在本脑字段里）。 */
    private final BaseBehavior barrage;
    /** 冲撞行为实例，常驻复用；是否可被选中由 crashCd/crashSoftCd 两个冷却共同决定。 */
    private final BaseBehavior crash;
    /** 压制弹幕冷却，单位 tick：行为结束时按 BEHAVIOR_COOLDOWN_* 区间写入，tickCooldowns() 每帧递减，>0 时不可被选中。 */
    private int barrageCd = 0;
    /** 冲撞硬冷却，单位 tick：同 barrageCd，但还叠加 crashSoftCd 的软冷却。 */
    private int crashCd = 0;
    /** 冲撞软冷却，单位 tick：冲撞结束后额外加的一层短冷却，防止冲撞连发导致玩家无法走位。 */
    private int crashSoftCd = 0;
    /** 平A（非技能的自瞄头颅）冷却，单位 tick：由 tickIdleAttack() 递减并在发射时重置为随机间隔。 */
    private int idleAttackCd = 0;

    /** 凋零的头数（主头 + 左右副头），用于 headShotCd 数组长度与遍历上界。 */
    private static final int HEAD_COUNT = 3;

    /** 三个头各自独立的射击冷却，单位 tick，下标 0=主头、1/2=副头；下标直接映射到 SkullVolley.fireFromHead 的 head 参数。 */
    private final int[] headShotCd = new int[HEAD_COUNT];

    /** 空闲窗口剩余时长，单位 tick：行为结束或无可选行为时写入，让凋零悬停片刻再重新决策，避免行为无缝连播。 */
    private int idleWindowTicks = 0;

    /** 上一帧是否处于空闲态的记录，仅供外部（动画/调试）查询，不再参与决策，避免与 idleWindowTicks 形成两份判据。 */
    private boolean lastTickIdle = false;

    /** 风筝时的横向绕行方向符号（+1/-1）：撞墙或超时后经 flipStrafeSign() 取反，避免朝同一个方向贴墙磨蹭。 */
    private double strafeSign = 1.0D;

    /** 最近一次撞墙的方向，供 PhaseOneLogic 在需要时反向绕行；无墙时为 Vec3.ZERO。 */
    private Vec3 lastWallDir = Vec3.ZERO;

    /** 距离下一次风筝决策还剩的 tick 数：>0 期间复用现有 kiteDir/kiteSpeed，保证方向与速度在决策间隔内保持稳定。 */
    private int kiteDecisionTicks = 0;

    /** 当前风筝移动方向（水平单位向量），null 表示尚未决策或已停止；仅在决策 tick 被重算。 */
    private Vec3 kiteDir = null;

    /** 当前风筝移动速度，单位 格/tick：与 kiteDir 同生共死，防止逐帧重算速度导致原地抖动或持续缓慢后撤。 */
    private double kiteSpeed = 0.0D;

    /** 一阶段当前技能编号：0=无、1=弹幕压制、2=BE 走位（取值语义见 skillName()）；两个技能互斥，同一时刻至多一个非 0。 */
    private int p1Skill = 0;

    /** 技能1（弹幕压制）的剩余持续 tick：>0 表示正在释放；归零后技能结束，才允许开始计 p1Skill1Cd。 */
    private int p1Skill1Ticks = 0;

    /** 技能2（BE 走位）的剩余持续 tick：BE 走位期间不射击，只做 1~2 段直线冲刺。 */
    private int p1Skill2Ticks = 0;

    /** 技能1 的冷却，单位 tick：从技能「结束」那一刻起算（不是从开始起算），避免 CD 与持续时间重叠导致技能窗口被吃掉。 */
    private int p1Skill1Cd = 0;

    /** 技能2 的冷却，单位 tick：与 p1Skill1Cd 同规则，同样从技能结束起算。 */
    private int p1Skill2Cd = 0;

    /** 本次 BE 走位要冲的段数（1~2 段），由技能开始时随机决定，段间可换方向。 */
    private int beDashSegments = 1;

    /** BE 走位当前已完成的段序号：与 beDashSegments 比较判断是否全部冲完。 */
    private int beDashSegmentIndex = 0;

    /** 当前这一段的剩余 tick：每帧递减，归零则进入下一段或结束技能。 */
    private int beDashTicksLeft = 0;

    /** BE 走位第一段的方向（水平单位向量），由 BeDash 在技能开始时选定。 */
    private Vec3 beDashDir = Vec3.ZERO;

    /** BE 走位第二段的方向；只有 beDashSegments==2 时才会被使用，通常与第一段不同以避免走出同一条直线。 */
    private Vec3 beDashDir2 = Vec3.ZERO;

    /** 狂暴模式标志：目标血量降到 50% 以下后置位，可逆；由 updateRageMode() 每帧根据 desire 刷新。 */
    private boolean rageMode = false;

    /** 狂暴粒子发射冷却，单位 tick：由 PhaseOneLogic.rageParticles() 递减与重置，控制粒子密度。 */
    private int rageParticleCd = 0;

    /** 二阶段行为顺序环形缓冲（最多 200 条）：由 record() 追加，供调试指令查看最近的行为序列，也用于 adjacentRepeatRate() 统计连发率。 */
    private final List<String> behaviorLog = new ArrayList<>();

    /** 本脑已存活的 tick 计数：tick() 每帧自增，供时序判断与调试显示（例如「入场后第几帧」）。 */
    private int tickCount = 0;

    /**
     * 【坐标所有权】本帧起点 X：tick() 开头从凋零身上抓取。
     * 本帧若无人登记 desired，mixin 就把凋零钉回这里（等效「原地冻结」），而不是放任原版速度把它带走。
     */
    private double baseX;
    /** 见 baseX；入场坠落时 pinFallFrame() 会直接改写本字段来推进下坠，而不是写速度。 */
    private double baseY;
    /** 见 baseX。 */
    private double baseZ;

    /** 本帧希望到达的 X 坐标，由 snapshotDesired() 在 moveBy/riseStep/applyAltitude 之后登记；仅当 hasDesired 为真时生效。 */
    private double desiredX;
    /** 见 desiredX。 */
    private double desiredY;
    /** 见 desiredX。 */
    private double desiredZ;
    /** 本帧是否登记过 desired 坐标：tick() 开头清假、snapshotDesired() 置真、pinFallFrame() 会主动压回 false。 */
    private boolean hasDesired = false;

    /** 是否允许本帧钉回坐标：仅当 tick() 至少跑过一次后才置真，防止尚未被状态机驱动的新凋零被钉到 (0,0,0)。 */
    private boolean armed = false;

    /** 二阶段入场坠落是否进行中：enterPhaseTwo() 置真、landPhaseTwoEntry() 置假；为真时 tick() 每帧交给 handlePhaseTwoFall()。 */
    private boolean fallActive = false;

    /** 坠落剩余 tick 上限（超时安全网）：虚空/超深洞穴里射线探不到地面时靠它强制就地触地，避免永久卡在坠落分支。 */
    private int fallTicksLeft = 0;

    /** 触地后的停顿剩余 tick：由 PHASE2_ENTRY_GROUND_PAUSE_TICKS 设置，归零即调用 landPhaseTwoEntry() 完成爆炸。 */
    private int fallPauseTicks = 0;

    /** 本次坠落的目标触地 Y 坐标：由 probeGroundY() 探测得到，也是下坠过程中禁止越过的下界。 */
    private double fallTargetY = 0.0D;

    /**
     * 私有构造：仅由 {@link #of(WitherBoss)} 惰性调用。
     * 副作用：缓存凋零与其所在维度，并预创建两个二阶段常驻行为实例（行为本身无内部状态，状态留在本脑字段里）。
     *
     * @param wither 本脑所服务的凋零实体
     */
    private WitherBrain(WitherBoss wither) {
        this.wither = wither;
        this.level = wither.level();
        this.barrage = new BarrageBehavior();
        this.crash = new CrashBehavior();
    }

    /**
     * 取得凋零对应的行为脑，不存在则创建并登记，是外部访问本类状态的唯一入口。
     * 用 UUID 而非实体引用作键：凋零实体可能被卸载/重建，UUID 始终稳定。
     *
     * @param wither 目标凋零实体
     * @return 该凋零的行为脑；同一 UUID 始终返回同一实例
     */
    public static WitherBrain of(WitherBoss wither) {
        return BRAINS.computeIfAbsent(wither.getUUID(), id -> new WitherBrain(wither));
    }

    /**
     * 注销凋零对应的行为脑，需在凋零死亡/被移除时调用。
     * 必要性：UUID 键永不复用，不注销则长期运行的服务器里 {@link #BRAINS} 会随每只凋零的出生无界增长。
     *
     * @param wither 即将消失的凋零实体
     */
    public static void forget(WitherBoss wither) {
        BRAINS.remove(wither.getUUID());
    }

    /**
     * 凋零的行为主循环，由凋零实体/事件层每 tick 调用一次，是全部战斗逻辑的驱动入口。
     *
     * <p>严格执行顺序（顺序本身是设计的一部分，调整会引起状态抖动）：
     * <ol>
     *   <li>登记坐标所有权起点（base* + hasDesired=false + armed=true）；</li>
     *   <li>死亡演出 —— 命中即 return，演出期间不得插入任何战斗行为；</li>
     *   <li>出生苏醒 —— 命中即 return，同理不得在激活动画里放技能/射击/回血；</li>
     *   <li>updateRegen() —— 必须在 updatePhase() 之前：否则进入二阶段的那一帧先回血会把血量推回阈值以上，
     *       导致 phase2 反复进入/退出（每 2 tick 抖一次并反复触发大爆炸）；</li>
     *   <li>updatePhase() → updateDesire() → updateRageMode()，随后是二阶段入场坠落（命中即 return）；</li>
     *   <li>tickHeadShotCds() —— 必须排在 handleIntro() 的早退之前：否则入场定格的每一帧都会冻住某个头的冷却；</li>
     *   <li>faceTarget() —— 每帧都调：原版 AI 已被取消，不接管朝向凋零会背对目标发射头颅；</li>
     *   <li>handleIntro() 早退，最后才是位移接管段（一阶段/二阶段逻辑派发）。</li>
     * </ol>
     *
     * <p>副作用：改坐标、改血量、改冷却、可能播粒子/音效与写日志；本帧不写 deltaMovement（见文件头约束）。
     */
    public void tick() {
        tickCount++;

        // 坐标所有权第 1 步：记录本帧起点并清空本帧位移意图。
        // 必须放在最前：后续逻辑只负责「登记 desired」；若本帧无人登记，mixin 会把凋零钉回 base*（等效冻结），
        // 而不是让它按原版残留速度乱飘。
        baseX = wither.getX();
        baseY = wither.getY();
        baseZ = wither.getZ();
        hasDesired = false;
        // armed=防止「尚未被本状态机驱动过的凋零」在 aiStep 里被钉到 (0,0,0)：
        // 只有 tick() 至少跑过一次、armed 置真后，mixin 侧 enforceNow() 才真正执行钉回。
        armed = true;

        // ② 死亡演出：致死一击被 LivingDamageEvent 改写成「刚好把血量压到 1.0」，收尾演出期间必须由演出接管，
        // 不能让状态机再插进技能/射击/回血，否则玩家看到的是「死亡动画被战斗行为打断」。
        if (WitherDeathSequence.tickFor(wither)) {
            return;
        }

        // ③ 出生苏醒：复用原版 invulnerableTicks 的激活动画（原地悬停 / 灵魂粒子 / 血条充能），
        // 演出期间同样禁止阶段评估、技能、射击与回血。
        if (updateAwakening()) {
            return;
        }

        // ④ 回血与阶段判定。顺序不可交换：先回血再判阶段时，刚进二阶段的那一帧回血会把血量顶回阈值之上，
        // 下一帧又被判回一阶段 —— 形成每 2 tick 进出二阶段的抖动，并反复触发入场大爆炸。
        updateRegen();
        updatePhase();
        updateDesire();

        // ⑤ 狂暴模式只跟随 desire（带 40 tick 防抖），必须在技能派发前刷新，供本帧的射击/权重使用。
        updateRageMode();

        // ⑥ 二阶段入场坠落：命中即整帧早退 —— 下坠与触地停顿期间不评估行为池、不放技能，只保留转头。
        if (handlePhaseTwoFall()) {
            return;
        }

        // ⑦ 三头冷却必须在这里扣减，且必须排在 handleIntro() 早退之前：
        // 入场定格的每帧若提前 return，某个头的冷却就会被永久冻住，直到定格结束才继续走表。
        tickHeadShotCds();

        // ⑧ 每帧强制对齐朝向：原版 AI 已被取消（本类接管位移后原版不再自己转向），
        // 不在这里转向的话凋零会背对目标发射头颅。
        faceTarget();

        // ⑨ 入场定格（P2 入场后 / 调试强制一阶段）优先于一切行为派发。
        if (handleIntro()) {
            return;
        }

        if (WitherConfig.OUR_MOVEMENT_TAKEOVER) {
            // 先掐断原版寻路：接管后位移不再走原版 MoveControl，留着它只会与坐标钉回互相打架。
            wither.getNavigation().stop();

            // ⑩ 未索敌总闸门 —— 行为池唯一的入口检查。
            // 成因：各技能「内部」大多做了目标空判，但「派发本身」不判，而 BeDash.pickForwardDirection
            // 在无目标时用随机水平方向兜底，于是会看到「没有仇恨还在地图上 BE 走位」。
            // 判据必须是 targetEntity() 而非 wither.getTarget()：后者可能是原版残留的失效目标，
            // 会让闸门误以为有目标而放行。
            if (targetEntity() == null) {

                stopAllBehaviors();
                return;
            }

            if (!phase2) {
                PhaseOneLogic.tick(this);

                if (rageMode) {
                    // 狂暴期的额外粒子表现，纯视觉，不参与决策。
                    PhaseOneLogic.rageParticles(this);
                }
            } else {
                PhaseTwoLogic.tick(this);

                // 行为池派发之后再修正姿态：贴脸并维持在离地 2~3 格，让近战武器够得着。
                phaseTwoMovement();
            }
        }
    }

    /**
     * 冻结全部战斗状态，把凋零拉回「无目标、无技能、无行为」的初始态。
     *
     * <p>调用时机：tick() 位移接管段检测到 targetEntity()==null 时。
     * <p>副作用：清空二阶段当前行为与空闲窗口、终止一阶段两个技能、重置 BE 走位（BeDash.reset）。
     * <p>为什么必须清跨帧残留：这些字段是跨帧存活的，目标一旦回来若从残缺状态续上，
     * 玩家会看到「卡顿一下又突然续上」的脱节动作，而不是干脆地重新起手。
     */
    private void stopAllBehaviors() {

        active = null;
        idleWindowTicks = 0;

        p1Skill = 0;
        p1Skill1Ticks = 0;
        p1Skill2Ticks = 0;
        com.fusionwither.wither.behavior.BeDash.reset(this);
    }

    /**
     * 推进入二阶段入场坠落，由 tick() 每帧调用（fallActive 为假时立刻返回 false）。
     *
     * <p>副作用：经 pinFallFrame() 改写 baseY 以推进下坠、递减 fallTicksLeft / fallPauseTicks，
     * 触地停顿结束后调用 landPhaseTwoEntry() 完成爆炸与召唤；本帧还会保持 faceTarget() 让凋零转向目标。
     *
     * @return true 表示本帧已被坠落流程独占，tick() 必须立刻 return，跳过行为池与技能派发
     *
     * <p>为什么改 baseY 而不写 deltaMovement：原版 aiStep 把 y 速度分量硬写为 0，
     * 想向下坠只能直接改坐标，再由 mixin 钉回。
     * <p>为什么需要 fallTicksLeft 超时兜底：虚空或极深洞穴里向下射线打不到地面，
     * 没有超时就会永久卡在这个早退分支（凋零悬在空中不再做任何事）。
     */
    private boolean handlePhaseTwoFall() {
        if (!fallActive) {
            return false;
        }

        faceTarget();

        // 触地停顿阶段：把凋零钉死在落点，倒计时归零后触发爆炸收尾；期间同样整帧早退。
        if (fallPauseTicks > 0) {
            fallPauseTicks--;
            pinFallFrame(fallTargetY);
            if (fallPauseTicks <= 0) {
                landPhaseTwoEntry();
            }
            return true;
        }

        // 超时安全网：射线始终没探到地面（虚空 / 超深洞穴）时，就地以上限时的目标高度触地，避免永久卡坠。
        if (fallTicksLeft <= 0) {
            NewMod.LOGGER.warn("[凋零/诊断] 凋灵 {} 二阶段入场坠落超时（目标 Y={}），强制就地触地",
                    wither.getUUID(), fallTargetY);
            pinFallFrame(fallTargetY);
            fallPauseTicks = WitherConfig.PHASE2_ENTRY_GROUND_PAUSE_TICKS;
            return true;
        }
        fallTicksLeft--;

        // 每帧至多下降 PHASE2_ENTRY_FALL_SPEED 格，且不得越过 fallTargetY（Math.max 在此即「下限夹取」）。
        double nextY = Math.max(baseY - WitherConfig.PHASE2_ENTRY_FALL_SPEED, fallTargetY);
        pinFallFrame(nextY);
        // 1.0E-4 是消除浮点误差的容差：两数相减可能残留极小尾数，用严格相等会漏判「已贴地」而多坠一帧。
        if (nextY <= fallTargetY + 1.0E-4D) {
            fallPauseTicks = WitherConfig.PHASE2_ENTRY_GROUND_PAUSE_TICKS;
            NewMod.LOGGER.info("[凋零/诊断] 凋灵 {} 二阶段入场：已坠落触地（Y={}），停顿 {} tick 后爆炸",
                    wither.getUUID(), nextY, fallPauseTicks);
        }
        return true;
    }

    /**
     * 把本帧的目标高度钉在指定 Y：改写 baseY，并把 hasDesired 压回 false。
     *
     * @param y 本帧希望凋零所处的 Y 坐标（下坠过程中为逐帧递减值，触地后为 fallTargetY）
     *
     * <p>为什么必须清 hasDesired：否则本帧更早登记过的 desired（例如 applyAltitude 的高度修正）
     * 会走 desired 分支、绕过这里设定的坠落作用点，让下坠被别的逻辑改写或抵消。
     */
    private void pinFallFrame(double y) {
        baseY = y;
        hasDesired = false;
    }

    /**
     * 用向下的射线探出凋零正下方的可站立地面高度，作为入场坠落的落点。
     *
     * @return 射线命中的 Y；未命中时返回 起点Y - PHASE2_ENTRY_FALL_MAX_DEPTH（即最大探测深度处）
     *
     * <p>为什么用射线而不是 onGround()：凋零碰撞箱宽约 3.5 格，且常悬在水面或可穿方块之上，
     * onGround() 的判定在这种体型下会飘；ClipContext.Block.COLLIDER 只认实体碰撞方块、
     * Fluid.NONE 忽略流体，得到的才是玩家真正能站的地面。
     * <p>最后 Math.min(hit.y, from.y)：命中点高于自身时不向上取，避免出现「向上坠落」的荒谬结果。
     */
    private double probeGroundY() {
        Vec3 from = wither.position();
        Vec3 to = from.add(0.0D, -WitherConfig.PHASE2_ENTRY_FALL_MAX_DEPTH, 0.0D);
        BlockHitResult hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, wither));
        if (hit.getType() == HitResult.Type.MISS) {
            return from.y - WitherConfig.PHASE2_ENTRY_FALL_MAX_DEPTH;
        }

        return Math.min(hit.getLocation().y, from.y);
    }

    /**
     * 完成二阶段入场落地：结束坠落、原地大爆炸、召唤凋灵骷髅、给凋零挂护甲、播入场视觉反馈，并重置行为冷却。
     *
     * <p>调用时机：坠落触地停顿结束（由 handlePhaseTwoFall() 触发）；未启用入场坠落时由 enterPhaseTwo() 直接调用。
     * <p>副作用：fallActive / fallPauseTicks / fallTicksLeft 归零；introTicks 置为 PHASE2_INTRO_TICKS 让凋零随后
     * 有一段入场定格；三种行为冷却清零，使入场后能立刻起手；护甲一旦挂上会反弹一切投射物。
     * <p>召唤数量在 PHASE2_SKELETON_SPAWN_MIN~MAX 间随机。
     */
    private void landPhaseTwoEntry() {
        fallActive = false;
        fallPauseTicks = 0;
        fallTicksLeft = 0;

        CrashEffects.bigExplosion(wither, level,
                WitherConfig.PHASE2_ENTRY_EXPLOSION_POWER,
                WitherConfig.PHASE2_ENTRY_BREAK_BLOCKS);

        int wanted = randint(WitherConfig.PHASE2_SKELETON_SPAWN_MIN,
                WitherConfig.PHASE2_SKELETON_SPAWN_MAX);
        WitherSkeletonPool.spawn(wither, level, wanted);

        WitherArmor.markArmored(wither);

        VisualFeedback.phaseTwoEntry(wither, level);

        barrageCd = 0;
        crashCd = 0;
        crashSoftCd = 0;

        introTicks = WitherConfig.PHASE2_INTRO_TICKS;

        NewMod.LOGGER.info("[凋零] 凋灵 {} 二阶段入场完成：已触地并爆炸（召唤骷髅 {} 只，随后入场定格 {}tick）",
                wither.getUUID(), wanted, WitherConfig.PHASE2_INTRO_TICKS);
    }

    /**
     * 二阶段入场坠落是否正在进行，供客户端表现/调试查询，不参与任何决策。
     *
     * @return true 表示凋零正处在入场下坠或触地停顿阶段
     */
    public boolean isPhaseTwoFalling() {
        return fallActive;
    }

    /**
     * 入场坠落的剩余 tick，用于外部显示坠落进度。
     *
     * @return 剩余坠落 tick；未处于坠落时为 0
     */
    public int phaseTwoFallTicksLeft() {
        return fallActive ? fallTicksLeft : 0;
    }

    /**
     * 二阶段姿态修正：把凋零维持在「贴近目标但离地 2~3 格」的高度，让玩家的近战武器够得着。
     *
     * <p>调用时机：tick() 位移接管段的二阶段分支，紧跟在 PhaseTwoLogic.tick(this) 的行为派发之后。
     * <p>副作用：经 moveBy() 登记水平位移、经 applyAltitude() 修正高度，两者都会改写 desired 坐标。
     * <p>特殊情况：当前行为是「冲撞」时直接返回 —— 冲撞自带位移，再叠加距离修正会让两套位移互相拉扯。
     */
    private void phaseTwoMovement() {
        LivingEntity t = targetEntity();
        if (t == null) {
            return;
        }

        if (active != null && "冲撞".equals(active.name())) {
            return;
        }
        Vec3 here = wither.position();
        Vec3 delta = here.subtract(t.position());
        // 只取水平投影：距离修正只管水平距离，高度一律交给 applyAltitude()。
        Vec3 flat = new Vec3(delta.x, 0.0D, delta.z);
        double dist = flat.length();
        if (dist > 1.0E-4D) {
            double preferred = desiredDistance();
            double err = dist - preferred;
            // 0.8 格死区：在期望距离附近不做微调，否则两帧内就会出现「进一点退一点」的抖动。
            if (Math.abs(err) >= 0.8D) {
                double strength = Math.min(1.0D, Math.abs(err) / preferred)
                        * WitherConfig.DISTANCE_CORRECTION_STRENGTH;
                // err>0 表示太远：沿目标方向靠近；否则表示太近：反向后退拉开距离。
                Vec3 dir = err > 0.0D ? flat.normalize().scale(-1.0D) : flat.normalize();
                // 步长带 0.02 格下限：修正强度不足时也要有可见位移，避免「几乎不动」的死区。
                moveBy(dir, Math.max(0.02D, strength * 0.35D));
            }
        }
        applyAltitude();
    }

    /**
     * 二阶段期望与目标保持的水平距离。
     *
     * @return 单位格；正在放压制弹幕时用 P2_BARRAGE_DISTANCE（拉远便于弹幕覆盖），否则用 P2_IDLE_DISTANCE
     */
    private double desiredDistance() {

        if (active != null && "弹幕压制".equals(active.name())) {
            return WitherConfig.P2_BARRAGE_DISTANCE;
        }
        return WitherConfig.P2_IDLE_DISTANCE;
    }

    /**
     * 期望的离地高度：一阶段高飞风筝用 P1_ALTITUDE，二阶段取 PHASE2_ALTITUDE_MIN/MAX 的中值（约 2~3 格）。
     *
     * @return 单位格，表示「在地面高度之上多少格」
     */
    private double desiredAltitude() {
        if (!phase2) {
            return WitherConfig.P1_ALTITUDE;
        }
        return (WitherConfig.PHASE2_ALTITUDE_MIN + WitherConfig.PHASE2_ALTITUDE_MAX) * 0.5D;
    }

    /**
     * 出生苏醒（激活动画）分支，由 tick() 在死亡演出判定之后、任何战斗逻辑之前调用。
     *
     * <p>不变量：返回 true 时 tick() 必须整帧早退 —— 苏醒期间禁止阶段评估、技能释放、射击与回血，
     * 否则原版激活动画会被战斗行为污染（边苏醒边放技能）。
     * <p>副作用：苏醒期间每帧 stopMoving()（不登记 desired，等价原地钉住）；用 awakeningAnnounced
     * 保证「进入苏醒 / 苏醒结束」各只写一条日志。
     *
     * @return true 表示凋零仍在出生苏醒期，tick() 应立即 return
     */
    private boolean updateAwakening() {
        if (!isAwakening()) {

            // 苏醒刚结束的这一帧补一条日志，随后本帧交还给正常战斗流程（返回 false）。
            if (awakeningAnnounced) {
                awakeningAnnounced = false;
                NewMod.LOGGER.info("[凋零/诊断] 凋灵 {} 出生苏醒结束：进入一阶段战斗（血量 {}/{}）",
                        wither.getUUID(), (float) wither.getHealth(),
                        (float) wither.getMaxHealth());
            }
            return false;
        }
        if (!awakeningAnnounced) {
            awakeningAnnounced = true;
            NewMod.LOGGER.info("[凋零/诊断] 凋灵 {} 进入出生苏醒：剩余 {} tick —— 钉在原地，不评估阶段、不放技能、不射击、不回血",
                    wither.getUUID(), wither.getInvulnerableTicks());
        }
        stopMoving();
        return true;
    }

    /**
     * 凋零是否处于出生苏醒期，判据是原版 invulnerableTicks>0。
     * 该字段只负责出生苏醒，与后续冲锋无关 —— 清零它并不能压制冲锋，别把它当成「通用演出开关」。
     *
     * @return true 表示仍处于激活动画中
     */
    public boolean isAwakening() {
        return wither.getInvulnerableTicks() > 0;
    }

    /**
     * 对外的期望离地高度，与私有 desiredAltitude() 同值，供其它类直接读取而不触碰内部状态。
     *
     * @return 单位格；二阶段为 PHASE2_ALTITUDE_MIN/MAX 的中值，一阶段为 P1_ALTITUDE
     */
    public double desiredAltitudePublic() {
        return phase2
                ? (WitherConfig.PHASE2_ALTITUDE_MIN + WitherConfig.PHASE2_ALTITUDE_MAX) * 0.5D
                : WitherConfig.P1_ALTITUDE;
    }

    /**
     * 把凋零的高度钉到「地面高度 + 期望离地高度」，是高度控制的统一入口（水平位移不在这里做）。
     *
     * <p>调用时机：二阶段每帧由 phaseTwoMovement() 调用；一阶段由 PhaseOneLogic 风筝时调用。
     * <p>副作用：setPos 改写 y、清零 deltaMovement/hasImpulse/fallDistance，最后 snapshotDesired() 登记本帧坐标。
     * <p>为什么不用速度：见文件头 —— 原版 aiStep 会把 y 速度分量硬写为 0，向上飞/向下压都只能坐标驱动。
     * <p>特殊情况：正在冲撞时直接返回，避免高度修正干扰冲撞轨迹。
     */
    public void applyAltitude() {

        if (active != null && "冲撞".equals(active.name())) {
            return;
        }

        net.minecraft.core.BlockPos pos =
                net.minecraft.core.BlockPos.containing(wither.getX(), wither.getY(), wither.getZ());
        // MOTION_BLOCKING_NO_LEAVES：取「会阻挡移动且不计树叶」的高度图，等价玩家实际可站的地表。
        int groundY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());

        double targetY = groundY + desiredAltitudePublic();
        double y = wither.getY();
        double diff = targetY - y;

        // 0.3 格死区：高度已在目标附近时不再修正，避免每帧小幅上下拉扯造成抖动。
        if (Math.abs(diff) < 0.3D) {
            return;
        }
        // 每帧垂直步长受 MOVEMENT_MAX_VERTICAL_SPEED 夹取，避免出现瞬移式升降。
        double vStep = Math.max(-WitherConfig.MOVEMENT_MAX_VERTICAL_SPEED,
                Math.min(WitherConfig.MOVEMENT_MAX_VERTICAL_SPEED, diff));

        wither.setPos(wither.getX(), y + vStep, wither.getZ());
        wither.setDeltaMovement(Vec3.ZERO);
        wither.hasImpulse = false;
        wither.fallDistance = 0.0F;

        snapshotDesired();
    }

    /**
     * 入场定格处理：introTicks>0 期间凋零原地不动，只保持朝目标转向。
     *
     * <p>调用时机：tick() 在 tickHeadShotCds() 与 faceTarget() 之后调用。
     * <p>副作用：递减 introTicks；stopMoving() 不登记 desired，等价于把凋零钉回本帧起点。
     * <p>写入者：landPhaseTwoEntry() 用 PHASE2_INTRO_TICKS 设定，调试指令可将它清零以立刻解除定格。
     *
     * @return true 表示本帧仍在定格中，tick() 应立即 return
     */
    private boolean handleIntro() {
        if (introTicks <= 0) {
            return false;
        }
        introTicks--;
        stopMoving();

        faceTarget();
        return true;
    }

    /**
     * 把凋零的朝向（视线/头部/身体）强制对齐到当前目标所在的水平方向。
     *
     * <p>调用时机：tick() 每帧一次；坠落分支与入场定格分支内部还会额外各调一次。
     * <p>原因：本类接管位移后原版 AI 不再自行转向，若不手动对齐，凋零会背对目标发射头颅。
     * <p>副作用：写 setYRot / setYHeadRot / yBodyRot；目标为空、或与目标水平距离²不足 1e-4 时直接返回，
     * 避免在正上/正下方时算出无意义的偏航角。
     */
    public void faceTarget() {
        LivingEntity t = targetEntity();
        if (t == null) {
            return;
        }
        double dx = t.getX() - wither.getX();
        double dz = t.getZ() - wither.getZ();
        if (dx * dx + dz * dz < 1.0E-4D) {
            return;
        }

        // MC 的 yaw 以 -Z 为 0°、顺时针为正，因此水平方向取 atan2(-dx, dz) 再转成角度。
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0D / Math.PI);
        wither.setYRot(yaw);
        wither.setYHeadRot(yaw);
        wither.yBodyRot = yaw;
    }

    /**
     * 供行为调用的一步冲刺：把方向压平成水平后交给 tryMove()（内部带路径碰撞检测）。
     *
     * @param dir   期望方向，y 分量会被丢弃
     * @param speed 本帧位移长度，单位格
     * @return true 表示前方被阻挡（未发生位移）；false 表示已成功位移
     */
    public boolean chargeForward(Vec3 dir, double speed) {
        Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return true;
        }

        return tryMove(flat, speed);
    }

    /**
     * 立即结束入场定格（把 introTicks 清零），供调试或特殊脚本使用。
     * 副作用：下一帧 handleIntro() 不再早退，凋零立刻恢复行为派发。
     */
    public void forceClearIntroInvulnerability() {
        introTicks = 0;
    }

    /**
     * 阶段判定：血量比例跌破阈值即进入二阶段；开关允许时血量回升则退回一阶段。
     *
     * <p>调用时机：tick() 每帧，且必须排在 updateRegen() 之后（否则刚进二阶段就会被回血顶回阈值之上）。
     * <p>副作用：向下会触发 enterPhaseTwo()（可能带上入场坠落）；回退分支清空 phase2 与当前行为。
     * <p>回退受 ALLOW_PHASE_REVERT 开关约束，且坠落进行中（fallActive）一律不允许回退，
     * 否则入场流程会被中途打断、导致落地爆炸不执行。
     */
    private void updatePhase() {
        double ratio = healthRatio();
        if (!phase2 && ratio <= WitherConfig.PHASE2_THRESHOLD) {
            enterPhaseTwo();
        } else if (phase2 && !fallActive
                && WitherConfig.ALLOW_PHASE_REVERT
                && ratio > WitherConfig.PHASE2_THRESHOLD) {
            phase2 = false;
            active = null;
            NewMod.LOGGER.debug("[凋零] 凋灵 {} 血量回到阈值以上，退回一阶段", wither.getUUID());
        }
    }

    /**
     * 进入二阶段的唯一入口：置阶段标志、清空一阶段技能状态，再按开关选择「坠落入场」或「直接落地」。
     *
     * <p>调用时机：updatePhase() 判定血量跌破阈值时；也可由 debugForcePhaseTwo() 强制调用。
     * <p>副作用：phaseTwoEntered 置真后本方法变成幂等（重复调用只刷新 phase2 并早退）；
     * 清空 active、一阶段两个技能与 BE 走位；启用坠落时初始化 fallActive/fallTicksLeft/fallTargetY 后
     * 立即 return —— 真正的爆炸、召唤与护甲都在落地时由 landPhaseTwoEntry() 完成。
     */
    private void enterPhaseTwo() {

        if (phaseTwoEntered) {
            NewMod.LOGGER.debug("[凋零] 凋灵 {} 忽略重复的二阶段触发", wither.getUUID());
            phase2 = true;
            return;
        }
        phaseTwoEntered = true;
        phase2 = true;
        active = null;

        p1Skill = 0;
        p1Skill1Ticks = 0;
        p1Skill2Ticks = 0;
        com.fusionwither.wither.behavior.BeDash.reset(this);

        if (WitherConfig.PHASE2_ENTRY_FALL_ENABLED) {
            fallActive = true;
            fallTicksLeft = WitherConfig.PHASE2_ENTRY_FALL_MAX_TICKS;
            fallPauseTicks = 0;
            fallTargetY = probeGroundY();
            NewMod.LOGGER.info("[凋零] 凋灵 {} 进入二阶段过渡：原地竖直坠落到地面后爆炸（起点 Y={}，探测到的地面 Y={}）",
                    wither.getUUID(), wither.getY(), fallTargetY);
            return;
        }

        landPhaseTwoEntry();
    }

    /**
     * 刷新欲望值 desire：目标血量比例 ≤ DESIRE_PLAYER_HP_THRESHOLD 记作高欲望(1.0)，否则 0.0。
     *
     * <p>调用时机：tick() 每帧（updatePhase 之后、updateRageMode 之前）。
     * <p>防抖：每次切换后进入 DESIRE_SWITCH_COOLDOWN_TICKS 倒计时，冷却未走完时保持原值不动，
     * 否则目标血量在阈值附近抖动会让行为权重与回血速率逐帧跳变。
     * <p>副作用：写 desire、写 desireCooldown，切换瞬间播一次 VisualFeedback.desireSwitched；
     * 无目标时直接返回（保留上一次的 desire）。
     */
    private void updateDesire() {
        if (desireCooldown > 0) {
            desireCooldown--;
        }

        LivingEntity target = targetEntity();
        if (target == null) {
            return;
        }
        double wanted = (target.getHealth() / target.getMaxHealth()
                <= WitherConfig.DESIRE_PLAYER_HP_THRESHOLD) ? 1.0D : 0.0D;
        if (wanted != desire && desireCooldown <= 0 && target != null) {
            desire = wanted;
            desireCooldown = WitherConfig.DESIRE_SWITCH_COOLDOWN_TICKS;
            VisualFeedback.desireSwitched(wither, level);
        }
    }

    /**
     * 自然回血：高欲望时按 REGEN_HIGH_DESIRE_PER_TICK、否则按 REGEN_NORMAL_PER_TICK（单位：点血/tick）。
     *
     * <p>调用时机：tick() 每帧，且必须排在 updatePhase() 之前（理由见 tick() 的顺序说明）。
     * <p>前置条件：凋零存活、不在入场定格（introTicks<=0）、血量未满，任一不满足即直接返回不回血。
     * <p>副作用：heal() 后立刻调用 clampRegenToPhaseTwo()，把二阶段血量压在阈值以下，
     * 保证「回血不会把凋零顶回一阶段」。
     */
    private void updateRegen() {
        if (wither.getHealth() <= 0.0F || wither.isDeadOrDying()) {
            return;
        }

        if (introTicks > 0) {
            return;
        }
        if (wither.getHealth() >= wither.getMaxHealth()) {
            return;
        }

        double rate = desire >= 0.5D
                ? WitherConfig.REGEN_HIGH_DESIRE_PER_TICK
                : WitherConfig.REGEN_NORMAL_PER_TICK;

        wither.heal((float) rate);
        clampRegenToPhaseTwo();
    }

    /**
     * 二阶段回血封顶：把血量压到「阈值 × 最大血量 - REGEN_PHASE2_MARGIN」以下。
     *
     * <p>为什么必须有：否则回血会把血量推过二阶段阈值，updatePhase() 随即把凋零判回一阶段，
     * 下一帧回血失败又判进二阶段 —— 每 2 tick 抖动一次并反复触发入场大爆炸。
     * <p>调用时机：updateRegen() 回血之后；从未进入过二阶段（!phase2 && !phaseTwoEntered）时直接返回。
     */
    private void clampRegenToPhaseTwo() {
        if (!phase2 && !phaseTwoEntered) {
            return;
        }
        double cap = wither.getMaxHealth() * WitherConfig.PHASE2_THRESHOLD
                - WitherConfig.REGEN_PHASE2_MARGIN;
        if (wither.getHealth() > cap) {
            wither.setHealth((float) cap);
        }
    }

    /**
     * 狂暴模式开关，判据是 desire>=0.5（等价于目标血量已跌破阈值），可逆。
     *
     * <p>调用时机：tick() 每帧，在 updateDesire() 之后、技能派发之前。
     * <p>进入时副作用：中断一阶段两个技能与 BE 走位，并用 staggerHeadShotCds() 重新错峰三头冷却
     * （否则狂暴瞬间三头会同一 tick 齐射）；退出时只清标志、不重置冷却。
     */
    private void updateRageMode() {
        boolean shouldRage = desire >= 0.5D;
        if (shouldRage && !rageMode) {
            rageMode = true;

            p1Skill = 0;
            p1Skill1Ticks = 0;
            p1Skill2Ticks = 0;
            com.fusionwither.wither.behavior.BeDash.reset(this);

            staggerHeadShotCds(WitherConfig.RAGE_MAIN_SHOT_INTERVAL_TICKS,
                    WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
            NewMod.LOGGER.info("[凋零] 凋灵 {} 进入狂暴模式（目标血量 ≤50%）", wither.getUUID());
        } else if (!shouldRage && rageMode) {
            rageMode = false;
            NewMod.LOGGER.info("[凋零] 凋灵 {} 退出狂暴模式（目标血量回到 >50%）", wither.getUUID());
        }
    }

    /**
     * 二阶段行为池调度：推进当前行为、维护空闲窗口，并在可选项里加权随机挑一个行为起手。
     *
     * <p>调用时机：tick() 位移接管段的二阶段分支（PhaseTwoLogic.tick(this) 内部调用）。
     * <p>流程：① 每帧先扣行为冷却、递减空闲窗口；② 有 active 则推进它，结束后按 BEHAVIOR_COOLDOWN_*
     * 随机写入对应冷却并进入空闲窗口；③ 空闲中交给 PhaseTwoLogic.idleWindow()；④ 否则收集「弹幕冷却已好」
     * 「冲撞硬/软冷却都已好」的可用行为，无可选则继续空闲；⑤ 按 desire 取高/普通两档权重做加权随机。
     * <p>顺带把 lastTickIdle 置为 false，保证「本帧是否空闲」只有这一处判据、不与 idleWindowTicks 漂移。
     */
    public void phaseTwoDispatch() {
        tickCooldowns();
        if (idleWindowTicks > 0) {
            idleWindowTicks--;
        }

        if (active != null) {
            lastTickIdle = false;
            active.tick();
            if (active.isFinished()) {
                // 冷却从「行为结束」这一刻起随机：填充大冷却的是弹幕，冲撞还要额外加一层软冷却。
                int cd = randint(WitherConfig.BEHAVIOR_COOLDOWN_MIN_TICKS,
                        WitherConfig.BEHAVIOR_COOLDOWN_MAX_TICKS);
                if (active == barrage) {
                    barrageCd = cd;
                } else {
                    crashCd = cd;
                    crashSoftCd = WitherConfig.CRASH_SOFT_COOLDOWN_TICKS;
                }
                active = null;

                idleWindowTicks = WitherConfig.IDLE_WINDOW_MIN_TICKS;
            }
            return;
        }

        if (idleWindowTicks > 0) {
            PhaseTwoLogic.idleWindow(this);
            return;
        }

        List<BaseBehavior> available = new ArrayList<>(2);
        if (barrageCd <= 0) {
            available.add(barrage);
        }
        if (crashCd <= 0 && crashSoftCd <= 0) {
            available.add(crash);
        }

        if (available.isEmpty()) {
            PhaseTwoLogic.idleWindow(this);
            return;
        }

        lastTickIdle = false;

        int wCrash = desire >= 0.5D ? WitherConfig.WEIGHT_CRASH_HIGH : WitherConfig.WEIGHT_CRASH_NORMAL;
        int wBarrage = desire >= 0.5D ? WitherConfig.WEIGHT_BARRAGE_HIGH : WitherConfig.WEIGHT_BARRAGE_NORMAL;
        BaseBehavior chosen = pick(available, wCrash, wBarrage);
        active = chosen;
        chosen.begin(this);
        record(chosen.name());
    }

    /**
     * 行为池的加权随机抽取：逐项累加权重，落在谁的权重区间里就返回谁。
     *
     * @param available 可用行为列表（本类里至多 2 项：压制弹幕与冲撞）
     * @param wCrash    冲撞权重，由调用方按 desire 给出高/普通两档
     * @param wBarrage  压制弹幕权重，同上
     * @return 被选中的行为实例
     *
     * <p>Math.max(0, w) 防止负权重破坏区间划分；权重总和 ≤0 时退化为等概率随机；
     * 末尾再兜底返回最后一项，覆盖取整导致的「未命中任何区间」。
     */
    private BaseBehavior pick(List<BaseBehavior> available, int wCrash, int wBarrage) {
        int total = 0;
        int[] weights = new int[available.size()];
        for (int i = 0; i < available.size(); i++) {
            BaseBehavior b = available.get(i);
            int w = (b == crash) ? wCrash : wBarrage;
            weights[i] = Math.max(0, w);
            total += weights[i];
        }
        if (total <= 0) {
            return available.get(random.nextInt(available.size()));
        }
        int roll = random.nextInt(total);
        int acc = 0;
        for (int i = 0; i < available.size(); i++) {
            acc += weights[i];
            if (roll < acc) {
                return available.get(i);
            }
        }
        return available.get(available.size() - 1);
    }

    /**
     * 平A（无技能时的自瞄头颅齐射）处理，由 PhaseTwoLogic 在空闲窗口里每帧调用。
     *
     * <p>冷却未归零时只递减并返回；否则在 OUR_ATTACK_ENABLED 开启且有目标时经 SkullVolley.fire()
     * 打一轮，并把 idleAttackCd 重置为 IDLE_ATTACK_INTERVAL_MIN/MAX 之间的随机值。
     * <p>它用的是独立于 headShotCd 的计时器：与技能共用会让技能窗口整段落在平A冷却里，
     * 出现「技能触发了却没发射」。
     */
    public void tickIdleAttack() {
        if (idleAttackCd > 0) {
            idleAttackCd--;
            return;
        }
        LivingEntity target = targetEntity();
        if (target != null && WitherConfig.OUR_ATTACK_ENABLED) {
            SkullVolley.fire(wither, target, false, WitherConfig.IDLE_ATTACK_DAMAGE);
        }
        idleAttackCd = randint(WitherConfig.IDLE_ATTACK_INTERVAL_MIN_TICKS,
                WitherConfig.IDLE_ATTACK_INTERVAL_MAX_TICKS);
    }

    /**
     * 递减三种行为冷却（压制弹幕 / 冲撞硬冷却 / 冲撞软冷却），单位 tick。
     * 调用时机：phaseTwoDispatch() 每帧开头；本方法只做扣减，是否可选由 phaseTwoDispatch() 自行判断。
     */
    private void tickCooldowns() {
        if (barrageCd > 0) barrageCd--;
        if (crashCd > 0) crashCd--;
        if (crashSoftCd > 0) crashSoftCd--;
    }

    /** @return 本脑所服务的凋零实体，供其它类查询（只读，不改变本脑状态）。 */
    public WitherBoss wither() {
        return wither;
    }

    /** @return 凋零所在维度，供其它类做射线检测与实体查询。 */
    public Level level() {
        return level;
    }

    /**
     * @return 本脑的随机源；对外暴露是为了让行为类与表现类复用同一条随机序，
     *         避免各自 new Random 导致冷却错峰/权重抽取不可复现。
     */
    public Random random() {
        return random;
    }

    /**
     * 闭区间随机整数 [min, max]。
     *
     * @param min 下界（含）
     * @param max 上界（含）
     * @return 随机整数；max<=min 时直接返回 min（避免 nextInt 因参数非法抛异常）
     */
    public int randint(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    /**
     * 半开区间随机浮点 [min, max)。
     *
     * @param min 下界（含）
     * @param max 上界（不含）
     * @return 随机浮点；max<=min 时直接返回 min
     */
    public double randdouble(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextDouble() * (max - min);
    }

    /**
     * 读取某个头当前的射击冷却。
     *
     * @param head 头下标：0=主头，1/2=左右副头
     * @return 剩余冷却，单位 tick；0 表示该头可以立即发射
     */
    public int headShotCd(int head) {
        return headShotCd[head];
    }

    /**
     * 设置某个头的射击冷却。
     *
     * @param head  头下标：0=主头，1/2=副头
     * @param ticks 剩余冷却，单位 tick；负数会被夹到 0，防止出现「冷却为负 = 立刻连发」
     */
    public void setHeadShotCd(int head, int ticks) {
        headShotCd[head] = Math.max(0, ticks);
    }

    /**
     * 三头冷却统一走表：每个头各减 1 tick，是「三头独立冷却」机制的心跳。
     *
     * <p>调用时机：tick() 中，且必须排在 handleIntro() 早退之前（见 tick() 的顺序说明），
     * 否则入场定格的每一帧都会把某个头的冷却冻住。
     * <p>历史坑：早期三头共用一个计时器，导致 (a) 技能窗口常整段落在平A冷却里、表现为「技能触发却没发射」；
     * (b) 三头永远在同一 tick 齐射，与原版表现不符。
     */
    public void tickHeadShotCds() {
        for (int head = 0; head < headShotCd.length; head++) {
            if (headShotCd[head] > 0) {
                headShotCd[head]--;
            }
        }
    }

    /**
     * 把三个头的冷却重新随机错峰：主头用 [0, mainMax]、两个副头各用 [0, sideMax]。
     *
     * <p>调用时机：updateRageMode() 确认进入狂暴模式的那一刻。
     * <p>为什么需要：进入狂暴前后各头冷却可能恰好都归零，不错峰就会三头同 tick 齐射。
     *
     * @param mainMax 主头冷却上限，单位 tick（下限固定为 0）
     * @param sideMax 副头冷却上限，单位 tick（下限固定为 0）
     */
    public void staggerHeadShotCds(int mainMax, int sideMax) {
        headShotCd[0] = randint(0, mainMax);
        headShotCd[1] = randint(0, sideMax);
        headShotCd[2] = randint(0, sideMax);
    }

    /**
     * 让所有冷却已就绪的头各发射一发凋零之首，是压制弹幕/连射技能的核心发射器。
     *
     * <p>调用时机：技能行为每帧调用（如 BarrageBehavior）；平A不走这里，走 tickIdleAttack()。
     * <p>副作用：对每个 headShotCd==0 的头调用 SkullVolley.fireFromHead()，随即把该头冷却重置为
     * 主头 [mainMin,mainMax]、副头 [sideMin,sideMax] 的随机值；已就绪的头互不影响，天然错峰。
     * <p>前置条件：target 非空且 OUR_ATTACK_ENABLED 开启，否则直接返回不发射。
     *
     * @param target  当前目标，可能为 null（表示未索敌，直接返回）
     * @param mainMin 主头冷却下限，单位 tick
     * @param mainMax 主头冷却上限，单位 tick
     * @param sideMin 副头冷却下限，单位 tick
     * @param sideMax 副头冷却上限，单位 tick
     * @param damage  单发伤害
     */
    public void fireReadyHeads(LivingEntity target, int mainMin, int mainMax,
                               int sideMin, int sideMax, double damage) {
        if (target == null || !WitherConfig.OUR_ATTACK_ENABLED) {
            return;
        }
        for (int head = 0; head < headShotCd.length; head++) {
            if (headShotCd[head] > 0) {
                continue;
            }
            SkullVolley.fireFromHead(wither, target, head, true, damage);
            headShotCd[head] = (head == 0)
                    ? randint(mainMin, mainMax)
                    : randint(sideMin, sideMax);
        }
    }

    /**
     * 清空本模组登记的凋零之首标记，用于技能被中断或收尾时避免残留弹幕继续生效。
     * 副作用：直接调用 SkullVolley.discardAllMarkers()，属于全局清理。
     */
    public void suppressOwnVolleys() {
        SkullVolley.discardAllMarkers();
    }

    /**
     * @return true 表示已进入二阶段；注意入场坠落与入场定格期间它同样为 true，
     *         需要区分「正在坠落」请用 isPhaseTwoFalling()
     */
    public boolean isPhaseTwo() {
        return phase2;
    }

    /** @return 当前风筝横向绕行方向符号：+1 或 -1。 */
    public double strafeSign() {
        return strafeSign;
    }

    /**
     * 反转风筝绕行方向符号；由 PhaseOneLogic 在撞墙或绕行超时后调用，
     * 否则凋零会一直朝同一侧贴墙磨蹭。
     */
    public void flipStrafeSign() {
        strafeSign = -strafeSign;
    }

    /** @return 最近一次撞墙的方向向量；从未撞过墙时为 Vec3.ZERO。 */
    public Vec3 lastWallDir() {
        return lastWallDir;
    }

    /**
     * 记录最近一次撞墙方向，供后续绕行决策参考（只登记，不立即改变移动）。
     *
     * @param d 撞墙方向向量
     */
    public void rememberWallDir(Vec3 d) {
        this.lastWallDir = d;
    }

    /** @return 距离下一次风筝决策还剩的 tick 数，单位 tick。 */
    public int kiteDecisionTicks() {
        return kiteDecisionTicks;
    }

    /**
     * 设置下一次风筝决策的倒计时。
     * @param v 剩余 tick 数，单位 tick
     */
    public void setKiteDecisionTicks(int v) {
        this.kiteDecisionTicks = v;
    }

    /** @return 当前风筝移动方向（水平单位向量）；null 表示尚未决策或已停止。 */
    public Vec3 kiteDir() {
        return kiteDir;
    }

    /**
     * 设置风筝移动方向；只在决策 tick 被重算，决策间隔内保持不变以避免原地抖动。
     * @param d 水平方向向量；null 表示停止风筝移动
     */
    public void setKiteDir(Vec3 d) {
        this.kiteDir = d;
    }

    /** @return 当前风筝移动速度，单位 格/tick。 */
    public double kiteSpeed() {
        return kiteSpeed;
    }

    /**
     * 设置风筝移动速度；与 kiteDir 同生共死，决策间隔内不逐帧重算，避免持续缓慢后撤。
     * @param v 速度，单位 格/tick
     */
    public void setKiteSpeed(double v) {
        this.kiteSpeed = v;
    }

    /**
     * 停止风筝移动：把 kiteDir 清空、kiteSpeed 归零。
     * 副作用：下一帧 PhaseOneLogic 不会基于旧方向继续位移，必须重新决策才会再动。
     */
    public void kiteStop() {
        this.kiteDir = null;
        this.kiteSpeed = 0.0D;
    }

    /** @return 一阶段当前技能编号：0=无、1=弹幕压制、2=BE 走位（语义见 skillName()）。 */
    public int p1Skill() {
        return p1Skill;
    }

    /**
     * 设置一阶段当前技能编号；由 PhaseOneLogic 在技能开始/结束时写入，两个技能互斥。
     * @param v 技能编号 0=无、1=弹幕压制、2=BE 走位
     */
    public void setP1Skill(int v) {
        this.p1Skill = v;
    }

    /** @return 技能1（弹幕压制）剩余持续 tick；>0 表示正在释放。 */
    public int p1Skill1Ticks() {
        return p1Skill1Ticks;
    }

    /**
     * 设置技能1（弹幕压制）的持续 tick；由 PhaseOneLogic 每帧递减、归零即结束技能。
     * @param v 剩余持续 tick
     */
    public void setP1Skill1Ticks(int v) {
        this.p1Skill1Ticks = v;
    }

    /** @return 技能2（BE 走位）剩余持续 tick；>0 表示正在冲刺（冲刺期间不射击）。 */
    public int p1Skill2Ticks() {
        return p1Skill2Ticks;
    }

    /**
     * 设置技能2（BE 走位）的持续 tick；由 BeDash 驱动递减、归零即结束技能。
     * @param v 剩余持续 tick
     */
    public void setP1Skill2Ticks(int v) {
        this.p1Skill2Ticks = v;
    }

    /** @return 技能1 冷却，单位 tick；从技能结束那一刻起算，>0 时不可再次起手。 */
    public int p1Skill1Cd() {
        return p1Skill1Cd;
    }

    /**
     * 设置技能1冷却；由 PhaseOneLogic 在技能1结束的瞬间写入（不是开始瞬间）。
     * @param v 冷却，单位 tick
     */
    public void setP1Skill1Cd(int v) {
        this.p1Skill1Cd = v;
    }

    /** @return 技能2 冷却，单位 tick；同样从技能结束那一刻起算。 */
    public int p1Skill2Cd() {
        return p1Skill2Cd;
    }

    /**
     * 设置技能2冷却；由 BeDash 在 BE 走位结束时写入。
     * @param v 冷却，单位 tick
     */
    public void setP1Skill2Cd(int v) {
        this.p1Skill2Cd = v;
    }

    /**
     * 技能编号转中文名，用于日志与调试输出。
     *
     * @param skill 技能编号 0/1/2
     * @return "无" / "弹幕压制" / "BE走位"；未识别的编号一律返回 "无"
     */
    public static String skillName(int skill) {
        return switch (skill) {
            case 1 -> "弹幕压制";
            case 2 -> "BE走位";
            default -> "无";
        };
    }

    /** @return 本次 BE 走位计划冲刺的段数（1 或 2）。 */
    public int beDashSegments() {
        return beDashSegments;
    }

    /**
     * 设置本次 BE 走位的段数；由 BeDash 在技能开始时随机决定。
     * @param v 段数，1 或 2
     */
    public void setBeDashSegments(int v) {
        this.beDashSegments = v;
    }

    /** @return BE 走位已完成（或正在执行）的段序号。 */
    public int beDashSegmentIndex() {
        return beDashSegmentIndex;
    }

    /**
     * 设置 BE 走位段序号；与 beDashSegments 比较即可判断是否已冲完全部段。
     * @param v 段序号
     */
    public void setBeDashSegmentIndex(int v) {
        this.beDashSegmentIndex = v;
    }

    /** @return 当前 BE 走位段的剩余 tick；归零后进入下一段或结束技能。 */
    public int beDashTicksLeft() {
        return beDashTicksLeft;
    }

    /**
     * 设置当前 BE 走位段的剩余 tick；由 BeDash 每帧递减。
     * @param v 剩余 tick
     */
    public void setBeDashTicksLeft(int v) {
        this.beDashTicksLeft = v;
    }

    /** @return BE 走位第一段的方向（水平单位向量）。 */
    public Vec3 beDashDir() {
        return beDashDir;
    }

    /**
     * 设置 BE 走位第一段方向；由 BeDash 在技能开始时选定。
     * @param d 水平方向向量
     */
    public void setBeDashDir(Vec3 d) {
        this.beDashDir = d;
    }

    /** @return BE 走位第二段的方向（水平单位向量）；仅当本次段数为 2 时才会被读取。 */
    public Vec3 beDashDir2() {
        return beDashDir2;
    }

    /**
     * 设置 BE 走位第二段方向；只有 beDashSegments==2 时才会被读取。
     * @param d 水平方向向量
     */
    public void setBeDashDir2(Vec3 d) {
        this.beDashDir2 = d;
    }

    /** @return true 表示当前处于狂暴模式（目标血量 ≤50% 期间）。 */
    public boolean isRageMode() {
        return rageMode;
    }

    /** @return 狂暴粒子发射冷却，单位 tick；仅用于控制粒子密度。 */
    public int rageParticleCd() {
        return rageParticleCd;
    }

    /**
     * 设置狂暴粒子冷却；由 PhaseOneLogic.rageParticles() 每帧递减与重置。
     * @param v 剩余冷却，单位 tick
     */
    public void setRageParticleCd(int v) {
        this.rageParticleCd = v;
    }

    /**
     * @return true 表示这只凋零至少经历过一次二阶段入场（之后不会再次触发坠落/爆炸/召唤），
     *         与「当前是否处于二阶段」（phase2）不是同一件事
     */
    public boolean hasEnteredPhaseTwo() {
        return phaseTwoEntered;
    }

    /** @return true 表示入场定格仍在进行（introTicks>0），期间凋零原地不动且不派发行为。 */
    public boolean isIntroPlaying() {
        return introTicks > 0;
    }

    /** @return 入场定格剩余 tick；0 表示定格已结束。 */
    public int introTicksLeft() {
        return introTicks;
    }

    /** @return 本脑自创建以来经过的 tick 数，用于时序判断与调试显示。 */
    public int tickCount() {
        return tickCount;
    }

    /**
     * 静态查询某只凋零是否处于二阶段（不创建行为脑，只读已存在的登记项）。
     *
     * @param wither 目标凋零实体
     * @return true 表示该凋零的二阶段标志为真；未登记行为脑时返回 false
     */
    public static boolean isPhaseTwoStatic(WitherBoss wither) {
        WitherBrain b = BRAINS.get(wither.getUUID());
        return b != null && b.phase2;
    }

    /** @return 上一帧是否被判为空闲（由 phaseTwoDispatch() 维护），供表现层查询。 */
    public boolean wasLastTickIdle() {
        return lastTickIdle;
    }

    /**
     * 设置「上一帧是否空闲」标志；只应由 phaseTwoDispatch() 与空闲窗口逻辑写入，
     * 外部读写它是为了表现层同步，不参与决策。
     * @param v 空闲标志
     */
    public void setLastTickIdle(boolean v) {
        this.lastTickIdle = v;
    }

    /** @return 当前欲望值 desire：1.0=高欲望，0.0=普通（带切换防抖）。 */
    public double getDesire() {
        return desire;
    }

    /**
     * @return true 表示当前为高欲望；这里用 desire>=0.5 作阈值而不是 ==1.0，
     *         以便将来把 desire 扩展成连续量时判据仍然成立
     */
    public boolean isHighDesire() {
        return desire >= 0.5D;
    }

    /** @return 二阶段当前正在执行的行为；null 表示空闲。 */
    public BaseBehavior activeBehavior() {
        return active;
    }

    /** @return 压制弹幕剩余冷却，单位 tick；0 表示可被选入行为池。 */
    public int getBarrageCd() {
        return barrageCd;
    }

    /** @return 冲撞硬冷却剩余，单位 tick。 */
    public int getCrashCd() {
        return crashCd;
    }

    /** @return 冲撞软冷却剩余，单位 tick；必须与硬冷却同时为 0 才允许再次冲撞。 */
    public int getCrashSoftCd() {
        return crashSoftCd;
    }

    /** @return 行为序列环形缓冲（最多 200 条）；返回的是内部引用，调用方只应读取。 */
    public List<String> behaviorLog() {
        return behaviorLog;
    }

    /**
     * 记录一次行为选择，供调试与连发率统计使用。
     *
     * <p>调用时机：phaseTwoDispatch() 每次起手一个行为、以及强制行为调试指令。
     * <p>副作用：追加到 behaviorLog，超过 200 条时丢弃最旧的一条（环形缓冲，防止长跑内存增长）；
     * DEBUG_LOG_BEHAVIOR_SEQUENCE 开启时额外写一条含欲望与三种冷却的日志。
     *
     * @param name 行为名称（弹幕压制/冲撞，调试时可能带「(强制)」后缀）
     */
    public void record(String name) {
        behaviorLog.add(name);
        if (behaviorLog.size() > 200) {
            behaviorLog.remove(0);
        }
        if (WitherConfig.DEBUG_LOG_BEHAVIOR_SEQUENCE) {
            NewMod.LOGGER.info("[凋零/行为序列] {} 欲望={} 可用CD(弹幕={} 冲撞={} 软={})",
                    name, desire >= 0.5D ? "高" : "消耗", barrageCd, crashCd, crashSoftCd);
        }
    }

    /**
     * 血量比例（0.0~1.0），是阶段判定的唯一输入。
     *
     * @return 当前血量 / 最大血量；最大血量 ≤0 时返回 0.0，避免除零
     */
    public double healthRatio() {
        float max = wither.getMaxHealth();
        return max <= 0.0F ? 0.0D : wither.getHealth() / max;
    }

    /**
     * 索敌唯一入口，四级优先级：
     * <ol>
     *   <li>原版仇恨落在合法玩家（存活、非创造、非旁观）→ 直接用；</li>
     *   <li>原版仇恨不可用 → 取周围 64 格内最近的合法玩家；</li>
     *   <li>仍无 → 原版仇恨若是其它存活且非玩家的活体，作为兜底目标；</li>
     *   <li>都没有 → 返回 null（行为池随总闸门整体停摆，凋零原地悬停）。</li>
     * </ol>
     *
     * <p>不自己扫描生物、只消费原版 getTarget()，因此「玩家优先」由原版 targetSelector 天然保证，
     * 本模组不需要也不应再写一套优先级。
     * <p>判据统一走本方法，不要直接用 wither.getTarget() —— 后者可能是原版残留的失效目标。
     *
     * @return 当前锁定目标；null 表示未索敌
     */
    public LivingEntity targetEntity() {
        LivingEntity raw = wither.getTarget();

        // 第①级：原版仇恨就是玩家且该玩家合法，直接采用。
        if (raw instanceof ServerPlayer sp && valid(sp)) {
            return sp;
        }

        // 第②级：原版仇恨不是合法玩家（或没有仇恨）时，退一步找 64 格内最近的合法玩家。
        ServerPlayer near = nearestValidPlayer();
        if (near != null) {
            return near;
        }

        // 第③级：连玩家都找不到时，才接受非玩家活体作为兜底；玩家在此始终优先于任何生物。
        if (raw != null && raw.isAlive() && !(raw instanceof Player)) {
            return raw;
        }

        return null;
    }

    /**
     * 在凋零碰撞箱外扩 64 格的范围内找最近的合法玩家，是索敌第②级的实现。
     *
     * <p>用 boundingBox.inflate 限定半径而非全维度扫描：开销固定，不随世界实体数量增长。
     *
     * @return 最近的合法玩家；范围内没有则返回 null
     */
    private ServerPlayer nearestValidPlayer() {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer sp : level.getEntitiesOfClass(ServerPlayer.class,
                wither.getBoundingBox().inflate(64.0D))) {
            if (!valid(sp)) {
                continue;
            }
            double d = sp.distanceToSqr(wither);
            if (d < bestDist) {
                bestDist = d;
                best = sp;
            }
        }
        return best;
    }

    /**
     * 判断玩家是否为有效攻击目标。
     *
     * @param sp 候选玩家
     * @return true 表示存活、非创造、非旁观；创造/旁观玩家即使站在身前也不会被凋零锁为仇恨
     */
    private static boolean valid(ServerPlayer sp) {
        return sp.isAlive() && !sp.isCreative() && !sp.isSpectator();
    }

    /**
     * 以坐标方式做一次水平位移：直接 setPos，清零速度/冲量/摔落距离，最后登记 desired 坐标。
     *
     * <p>调用时机：PhaseOneLogic 风筝、BeDash 冲刺、phaseTwoMovement 距离修正等一切需要位移的地方。
     * <p>为什么不用速度：原版 aiStep 只在水平距离平方>9 时才写一次速度、且 y 分量硬写为 0，
     * 写速度既不可靠，又会与混入的坐标钉回互相打架。
     * <p>零向量保护：水平分量平方 <1e-6 时直接返回，避免 normalize 产生 NaN 坐标。
     *
     * @param dir   方向向量；y 分量会被丢弃（本方法只做水平位移）
     * @param speed 本帧位移长度，单位格
     */
    public void moveBy(Vec3 dir, double speed) {
        Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return;
        }
        flat = flat.normalize();
        Vec3 p = wither.position();
        wither.setPos(p.x + flat.x * speed, p.y, p.z + flat.z * speed);
        wither.setDeltaMovement(Vec3.ZERO);
        wither.hasImpulse = false;
        wither.fallDistance = 0.0F;

        snapshotDesired();
    }

    /**
     * moveBy() 的别名，供行为类以「推一把」的语义调用（实现完全相同）。
     *
     * @param dir   方向向量；y 分量会被丢弃
     * @param speed 本帧位移长度，单位格
     */
    public void push(Vec3 dir, double speed) {
        moveBy(dir, speed);
    }

    /**
     * 停止移动：只把速度与冲量清零，不改变坐标、也不登记 desired。
     *
     * <p>因此若本帧没有其它位移登记，凋零会被 mixin 钉回本帧起点（等效原地不动）——
     * 这正是「入场定格」「出生苏醒」想要的静止效果。
     */
    public void stopMoving() {
        wither.setDeltaMovement(Vec3.ZERO);
        wither.hasImpulse = false;
    }

    /**
     * 以坐标方式做一次竖直位移（抬升 dy 格，可为负表示下降）。
     *
     * <p>为什么单独开一个方法：原版 aiStep 的 y 速度分量被硬写为 0，升降只能直接改坐标；
     * 与 moveBy() 一样会清零速度/冲量/摔落距离，并登记 desired。
     *
     * @param dy 竖直位移，单位格；正数向上、负数向下
     */
    public void riseStep(double dy) {
        wither.setPos(wither.getX(), wither.getY() + dy, wither.getZ());
        wither.setDeltaMovement(Vec3.ZERO);
        wither.hasImpulse = false;
        wither.fallDistance = 0.0F;

        snapshotDesired();
    }

    /**
     * 把凋零当前坐标登记为本帧的 desired 坐标，并置 hasDesired=true。
     *
     * <p>调用时机：moveBy / riseStep / applyAltitude 在 setPos 之后调用。
     * <p>语义：等价于声明「本帧我确实想停在/去到这个坐标」，
     * mixin 侧据此把凋零钉到 desired* 而不是 base*。
     */
    private void snapshotDesired() {
        desiredX = wither.getX();
        desiredY = wither.getY();
        desiredZ = wither.getZ();
        hasDesired = true;
    }

    /**
     * 坐标所有权的落地执行：把凋零钉到本帧登记的目标坐标。
     *
     * <p>调用时机：由 WitherBossMovementMixin 注入原版 aiStep(m_8107_) 的 TAIL，经 enforceMovement() 每帧调用一次。
     * <p>逻辑：armed 未置真直接返回（还没被状态机跑过的新凋零不能钉）；随后 armed 置假，保证每 tick 只钉一次；
     * hasDesired 为真就钉 desired*，否则钉 base*；最后清零速度/冲量/摔落距离。
     * <p>为什么一定要清零速度：否则原版速度会在下一帧把凋零从钉好的位置上拖走，坐标控制失效。
     */
    public void enforceNow() {

        if (!armed) {
            return;
        }
        armed = false;
        if (hasDesired) {
            wither.setPos(desiredX, desiredY, desiredZ);
        } else {

            wither.setPos(baseX, baseY, baseZ);
        }
        wither.setDeltaMovement(Vec3.ZERO);
        wither.hasImpulse = false;
        wither.fallDistance = 0.0F;
    }

    /**
     * mixin 侧的静态入口：判断这只凋零该不该被钉，再转发给对应行为脑的 enforceNow()。
     *
     * <p>调用时机：WitherBossMovementMixin 注入原版 aiStep(m_8107_) 的 TAIL。
     * <p>前置条件：OUR_MOVEMENT_TAKEOVER 开启；客户端实例、已死亡与已移除的凋零一律跳过
     * （客户端本地无需钉位置；死亡/移除阶段钉位置会干扰演出与清理流程）。
     * <p>副作用：调用 brain.enforceNow()，即改写凋零坐标为 desired* 或 base* 并清零速度。
     *
     * @param wither 注入点上的凋零实例
     */
    public static void enforceMovement(WitherBoss wither) {
        if (!WitherConfig.OUR_MOVEMENT_TAKEOVER) {
            return;
        }
        if (wither.level().isClientSide() || wither.isDeadOrDying() || wither.isRemoved()) {
            return;
        }
        WitherBrain brain = BRAINS.get(wither.getUUID());
        if (brain != null) {
            brain.enforceNow();
        }
    }

    /**
     * 预判沿 dir 方向前进 probe 格是否会撞上碰撞箱（只做一次终点检测，不做分段采样）。
     *
     * <p>调用时机：行为逻辑在敲定方向前做可行性判断。
     * <p>与 tryMove() 的区别：本方法只回答「能不能走」，绝不改坐标；tryMove() 会在可行时真的移动。
     *
     * @param dir   方向向量，y 分量被丢弃
     * @param probe 探测距离，单位格
     * @return true 表示前方无碰撞、可以移动；false 表示被阻挡或方向退化
     */
    public boolean canMove(Vec3 dir, double probe) {
        Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return false;
        }
        flat = flat.normalize();
        return level.noCollision(wither,
                wither.getBoundingBox().move(flat.x * probe, 0.0D, flat.z * probe));
    }

    /**
     * 带路径碰撞检测的一步位移：全程无阻挡才调用 moveBy()，否则原地不动。
     *
     * <p>调用时机：BeDash 冲刺、CrashBehavior 冲撞、chargeForward() 等高速位移场合。
     * <p>为什么分段采样：只检测终点的话，高速移动可能直接「穿」过薄墙；
     * 这里按 MOVE_PATH_CHECK_STEP 把整段位移切成若干采样点，任一点碰撞即判定受阻。
     * <p>副作用：未受阻时经 moveBy() 改坐标并登记 desired；受阻时只 stopMoving()，不产生位移。
     *
     * @param dir   方向向量，y 分量被丢弃
     * @param speed 本帧位移长度，单位格
     * @return true 表示被阻挡（未移动）；false 表示已成功位移
     */
    public boolean tryMove(Vec3 dir, double speed) {
        Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            stopMoving();
            return false;
        }
        flat = flat.normalize();

        boolean blocked = false;
        double step = WitherConfig.MOVE_PATH_CHECK_STEP;
        if (step > 0.0D && speed > step) {
            int samples = (int) Math.ceil(speed / step);
            for (int i = 1; i <= samples; i++) {
                double t = (double) i / samples;
                if (!level.noCollision(wither,
                        wither.getBoundingBox().move(flat.x * speed * t, 0.0D, flat.z * speed * t))) {
                    blocked = true;
                    break;
                }
            }
        }
        if (!blocked) {
            blocked = !level.noCollision(wither,
                    wither.getBoundingBox().move(flat.x * speed, 0.0D, flat.z * speed));
        }

        if (!blocked) {
            moveBy(flat, speed);
        }
        return blocked;
    }

    /**
     * 生成一个随机的水平单位向量（方位角在 0~2π 上均匀分布）。
     * 供需要「无目标也要选个方向」的场合兜底使用，例如 BeDash 的 pickForwardDirection。
     *
     * @return 水平单位向量，y 分量为 0
     */
    public Vec3 randomHorizontal() {
        double a = random.nextDouble() * Math.PI * 2.0D;
        return new Vec3(Math.cos(a), 0.0D, Math.sin(a));
    }

    /**
     * 调试指令入口：强制进入二阶段（未处于二阶段时调用 enterPhaseTwo()）。
     * 副作用：与自然进入二阶段完全一致，可能触发入场坠落；已进入时不做任何事。
     */
    public void debugForcePhaseTwo() {
        if (!phase2) {
            enterPhaseTwo();
        }
    }

    /**
     * 调试指令入口：强制退回一阶段。
     * 副作用：清空 phase2、active、入场定格；把 phaseTwoEntered 复位 —— 与自然回退不同，
     * 复位后允许再次触发二阶段入场演出；同时恢复凋零护甲。
     */
    public void debugForcePhaseOne() {
        phase2 = false;
        active = null;
        introTicks = 0;

        phaseTwoEntered = false;

        WitherArmor.restoreArmor(wither);
    }

    /**
     * 调试指令入口：清空全部行为冷却与入场定格，使行为池立刻可用。
     * 副作用：barrageCd / crashCd / crashSoftCd / idleAttackCd 归零，active 置空，introTicks 归零。
     */
    public void debugResetCooldowns() {
        barrageCd = 0;
        crashCd = 0;
        crashSoftCd = 0;
        idleAttackCd = 0;
        active = null;
        introTicks = 0;
    }

    /**
     * 调试指令入口：按参数统一切换阶段。
     * @param v true → 调 debugForcePhaseTwo()，false → 调 debugForcePhaseOne()
     */
    public void debugSetPhaseTwo(boolean v) {
        if (v) {
            debugForcePhaseTwo();
        } else {
            debugForcePhaseOne();
        }
    }

    /**
     * 调试指令入口：无视冷却与权重，直接强制某个行为立刻起手。
     *
     * <p>副作用：先 debugResetCooldowns()，再把 active 指向指定行为并调用 begin()；
     * 未知名字不做任何事。
     *
     * @param name 行为名："barrage"=压制弹幕、"crash"=冲撞（大小写不敏感）
     */
    public void debugForceBehavior(String name) {
        debugResetCooldowns();
        if ("barrage".equalsIgnoreCase(name)) {
            active = barrage;
            active.begin(this);
            record("barrage(强制)");
        } else if ("crash".equalsIgnoreCase(name)) {
            active = crash;
            active.begin(this);
            record("crash(强制)");
        }
    }

    /**
     * 调试指令入口：把两种行为冷却同时设为指定值，用来观察冷却期内凋零的行为。
     * @param ticks 冷却，单位 tick；同时写入弹幕冷却与冲撞硬冷却，软冷却清零，active 置空
     */
    public void debugForceCooldowns(int ticks) {
        barrageCd = ticks;
        crashCd = ticks;
        crashSoftCd = 0;
        active = null;
    }

    /**
     * 统计行为序列中「相邻两次相同」的比例，是行为连发的量化指标。
     *
     * <p>用途：调试加权随机是否过于集中（例如连续两轮都是冲撞）。
     *
     * @return 0.0~1.0 的比例值；日志不足 2 条时返回 0.0
     */
    public double adjacentRepeatRate() {
        if (behaviorLog.size() < 2) {
            return 0.0D;
        }
        int repeats = 0;
        for (int i = 1; i < behaviorLog.size(); i++) {
            if (behaviorLog.get(i).equals(behaviorLog.get(i - 1))) {
                repeats++;
            }
        }
        return (double) repeats / (behaviorLog.size() - 1);
    }
}

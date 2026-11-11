/*
 * 【文件职责】一阶段的战斗调度：远距离风筝走位 + 两个互斥技能（压制弹幕 / BE 位移），
 *             并在目标血量比例 ≤ 50% 时整体切换为「狂暴模式」（直线追杀 + 三头连续射击）。
 * 【所属模块】wither.phase 阶段逻辑。
 * 【协作关系】由 WitherBrain.tick() 在「非二阶段」且未进入死亡演出/激活动画时每帧调用 tick()；
 *             走位形状依赖 behavior.KiteMove 与 behavior.BeDash，
 *             发射动作委托 WitherBrain.fireReadyHeads → SkullVolley，
 *             狂暴视觉走 VisualFeedback.rageAura。
 * 【关键约束】本类**不持有任何字段**，全部状态（技能 CD、技能时长、三头射击冷却）都存在
 *             WitherBrain 上并由它统一扣减 —— 同一语义只能有一处计时，
 *             否则会重现「技能触发了却没发射」的历史症状。
 */
package com.fusionwither.wither.phase;

import com.fusionwither.NewMod;
import com.fusionwither.wither.SkullVolley;
import com.fusionwither.wither.VisualFeedback;
import com.fusionwither.wither.WitherBrain;
import com.fusionwither.wither.WitherConfig;
import com.fusionwither.wither.behavior.BeDash;
import com.fusionwither.wither.behavior.KiteMove;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.Vec3;

/**
 * 一阶段战斗逻辑：风筝消耗 + 双技能调度，目标血量 ≤ 50% 后进入狂暴模式。
 *
 * <p><b>角色：</b>本类只回答「这一帧该跑哪个技能、该不该开火、该往哪走」，
 * 具体的坐标位移、冷却存储、发射动作全部交给 {@link WitherBrain}。
 * 这样把「决策」与「执行」分开，位移和射击各只有一个实现入口。</p>
 *
 * <p><b>两段战斗：</b>
 * <ul>
 *   <li><b>消耗模式</b>（狂暴判定为假）：{@link KiteMove} 维持距离风筝走位 + 主头平A，
 *       其间穿插两个<b>互斥</b>技能 —— ①压制弹幕（原地悬停、三头错峰开火）
 *       ②BE 位移（无攻击的 1~2 段直线冲刺）；</li>
 *   <li><b>狂暴模式</b>：{@link #rushdown} 直奔目标贴脸 + {@link #rageVolley} 三头独立连续射击。
 *       整段<b>不走技能调度</b>，所以 BE 位移在狂暴期间天然禁用。</li>
 * </ul></p>
 *
 * <p><b>为什么阶段切换判「血量比例」而不是「绝对血量」：</b>
 * {@link WitherBrain#isRageMode()} 的取值来自 {@code WitherBrain.updateDesire()}，
 * 它比较的是 {@code target.getHealth() / target.getMaxHealth()} 与阈值，
 * 即目标<b>当前血量占其上限的比例</b>。用比例而不是写死某个血量数字，是因为目标的最大血量
 * 会随难度、装备、其它模组变化 —— 若用绝对阈值（例如 10 点），强化过血量的目标可能在满血时就
 * 已经低于阈值，凋灵一进场就狂暴，一阶段等于不存在；用比例则任何最大血量下都保证
 * 「打掉一半才狂暴」。这个判定还带 40 tick 防抖（见 {@code desireCooldown}），
 * 且是<b>可逆</b>的：目标回血到 &gt; 50% 即退出狂暴，因此本类每帧都重新读，
 * 不做一次性 latch（否则「半血后迅速回满」会让凋灵永远卡在追杀模式）。</p>
 *
 * <p><b>不变量：</b>每个分支在返回前都必须调用一次 {@code brain.applyAltitude()}。
 * 坐标所有权机制下，本帧不登记任何位移意图就会被 MovementMixin 钉回本帧起点，
 * 二阶段的高度维持同理；漏掉这一步的表现是高度停在上一次的值。</p>
 */
public final class PhaseOneLogic {

    private PhaseOneLogic() {}

    /**
     * 一阶段每帧入口：按优先级挑出本帧要做的事。
     *
     * <p><b>调用时机：</b>由 {@link WitherBrain#tick()} 在「非二阶段」时每帧调用一次，
     * 且已在其内部通过「无目标则总闸门早退」保证本方法拿到的 brain 一定有目标
     * （本类的 {@code targetEntity()} 调用仍会对 null 做防御）。</p>
     *
     * <p><b>分支优先级（高 → 低）：</b>
     * <ol>
     *   <li>狂暴模式：立刻追杀 + 连续射击，直接 return，跳过全部技能调度；</li>
     *   <li>技能进行中（{@code p1Skill != 0}）：继续推进该技能；</li>
     *   <li>无技能：先尝试触发技能，触发成功则本帧就地执行一次（不白吃这一帧），
     *       否则退回风筝走位 + 平A 的常态。</li>
     * </ol></p>
     *
     * <p><b>副作用：</b>会改写技能 CD / 技能时长 / 技能槽位，会登记本帧位移意图
     * （经 {@code moveBy}，最终由 MovementMixin 落地），可能发射凋灵之首、
     * 播音效（经 SkullVolley），并在技能 1 启动时写一行诊断日志。</p>
     *
     * @param brain 该凋灵的状态容器；所有读写都经它的访问器，不存在本类的副本
     */
    public static void tick(WitherBrain brain) {
        // 说明：三颗头的射击冷却由 WitherBrain.tick() 每帧统一扣减一次
        // （见 tickHeadShotCds）—— 本类不再维护任何射击计时，
        // 避免"两处各自计时"再次漂移成技能窗口被平A 冷却吃掉的旧症状。

        // 狂暴模式：追杀 + 三头独立射击常态，无技能调度
        if (brain.isRageMode()) {
            rushdown(brain);
            rageVolley(brain);
            return;
        }

        // ---- 消耗模式：技能优先 ----
        // 【顺序很关键】先扣减 CD，再判断"本帧该执行哪个技能"，
        // 并与技能的执行放在**同一个分支**里。
        //
        // 早期实现是"tickSkills() 先在内部扣减并 return，随后 tick() 再读
        // brain.p1Skill() 决定执行哪个" —— 那样当某一帧把技能计时扣到 0、
        // 在同一帧内结束技能时，随后读到的 skill 已经是 0，
        // 于是**那一帧的技能位移被整个跳过**。
        // BE 走位尤其明显：段切换也写在技能 tick 里，
        // 切换帧被跳过就同时损失了一帧位移与一次段切换。
        tickCooldowns(brain);

        if (brain.p1Skill() == 1) {
            // 技能1：弹幕压制 —— **原地悬停输出**（用户定稿）
            //
            // 【实机反馈修复 · 第二轮】"压制"必须真的开火，而且开火必须是原版那种
            // "三颗头各自独立"的观感。上一轮这里补的是一句 normalVolley
            // （三颗头在同一 tick 齐射），实测有两个问题：
            //   ① 它与平A 共用同一个计时器（平A 间隔 40~80t），
            //      而技能窗口只有 50~100t 中的一部分 —— 窗口经常整段落在平A 冷却里，
            //      一帧都不发射，玩家看到的就是"一阶段根本不触发压制弹幕"
            //      （日志里技能其实每次都触发了）；
            //   ② 症状之二：残血狂暴时是"三个头一起发射"。
            // 现在：技能触发时已把三颗头的冷却错峰初始化（见 tryStartSkill），
            // 本帧起每颗头按自己的节奏开火 —— 主头 40t、两颗副头各 40~60t。
            brain.stopMoving();
            fireBarrage(brain);
            tickSkill1(brain);
            brain.applyAltitude();   // 原地悬停，但仍要把高度维持在期望值
            return;
        }
        if (brain.p1Skill() == 2) {
            // 技能2：BE 走位 —— 只走冲刺，期间禁止攻击
            BeDash.tick(brain);
            tickSkill2(brain);
            brain.applyAltitude();
            return;
        }

        // ---- 无技能：尝试触发，否则风筝 + 平A ----
        if (tryStartSkill(brain)) {
            // 触发的那一帧也执行一次技能内容，避免白吃一帧
            if (brain.p1Skill() == 1) {
                brain.stopMoving();
                fireBarrage(brain);   // 触发帧同样开火（与上面的常驻分支一致）
                tickSkill1(brain);
            } else if (brain.p1Skill() == 2) {
                BeDash.tick(brain);
                tickSkill2(brain);
            }
            brain.applyAltitude();
            return;
        }
        KiteMove.tick(brain);
        basicAttack(brain);
        brain.applyAltitude();
    }

    // =================================================================
    //  技能调度器：CD 扣减 → 技能选择 → 技能计时与收尾
    // =================================================================

    /**
     * 把两个技能的冷却各扣 1 tick（减到 0 为止）。
     *
     * <p><b>调用时机：</b>{@link #tick} 的消耗模式分支开头，每帧一次 —— 必须早于
     * 「本帧该执行哪个技能」的判定，否则就会出现上面注释里那段「同一帧结束技能
     * 导致那一帧位移被跳过」的错位。</p>
     *
     * <p><b>副作用：</b>写 {@code p1Skill1Cd} / {@code p1Skill2Cd}。
     * 狂暴模式不经过这里，因此狂暴期间两个技能 CD 停在原地。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void tickCooldowns(WitherBrain brain) {
        if (brain.p1Skill1Cd() > 0) {
            brain.setP1Skill1Cd(brain.p1Skill1Cd() - 1);
        }
        if (brain.p1Skill2Cd() > 0) {
            brain.setP1Skill2Cd(brain.p1Skill2Cd() - 1);
        }
    }

    /**
     * 技能1（压制弹幕）的计时递减与收尾。
     *
     * <p><b>调用时机：</b>技能1 的每一帧（含触发帧）。</p>
     * <p><b>结束条件：</b>计时归零即结束 —— 压制弹幕是「原地悬停持续开火」的单段技能，
     * 没有 BE 位移那种多段的概念，因此不需要额外的完成判据。</p>
     *
     * <p><b>为什么 CD 从「结束这一帧」就开始计：</b>本方法在 {@code left <= 0} 的同一帧里
     * 直接写入新 CD，而不是等下一帧再写。这样「两次技能开始」的间隔严格等于配置的
     * CD 值，不会凭空空出额外一帧（用户定稿的 CD 语义）。</p>
     *
     * <p><b>副作用：</b>写 {@code p1Skill1Ticks}；结束时把 {@code p1Skill} 归 0、
     * 重掷技能1 CD、写一行战况记录。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void tickSkill1(WitherBrain brain) {
        int left = brain.p1Skill1Ticks() - 1;
        brain.setP1Skill1Ticks(left);
        if (left <= 0) {
            brain.setP1Skill(0);
            brain.setP1Skill1Cd(brain.randint(
                    WitherConfig.P1_BARRAGE_CD_MIN_TICKS,
                    WitherConfig.P1_BARRAGE_CD_MAX_TICKS));
            brain.record("技能1弹幕压制(结束)");
        }
    }

    /**
     * 技能2（BE 位移）的计时递减与收尾。
     *
     * <p><b>调用时机：</b>技能2 的每一帧（含触发帧）。</p>
     * <p><b>为什么要附加 {@link BeDash#finished} 判据：</b>计时时长只保证技能的最长占用，
     * 真正的结束以「内部那 1~2 段冲刺全部走完」为准。两个条件取与（必须在计时归零
     * 且段走完时才收尾），否则段还没走完就切回常态，断在冲刺中途。</p>
     *
     * <p><b>副作用：</b>写 {@code p1Skill2Ticks}；结束时归 0 技能槽位、重掷技能2 CD、
     * 调用 {@link BeDash#reset} 清空技能内部状态，并写一行战况记录。
     * 清状态必须与 {@link BeDash#begin} 严格对称 —— 残留值在当时可能无害，
     * 但将来某次改动后就会生效（V-01 同型问题：同一语义的两份状态不同步）。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void tickSkill2(WitherBrain brain) {
        int left = brain.p1Skill2Ticks() - 1;
        brain.setP1Skill2Ticks(left);
        if (left <= 0 && BeDash.finished(brain)) {
            brain.setP1Skill(0);
            brain.setP1Skill2Cd(brain.randint(
                    WitherConfig.P1_BE_DASH_CD_MIN_TICKS,
                    WitherConfig.P1_BE_DASH_CD_MAX_TICKS));
            // 清空技能内部状态 —— 与 BeDash.begin 对称，
            // 避免残留值在将来某次改动后生效（V-01 同型问题）。
            BeDash.reset(brain);
            brain.record("技能2BE走位(结束)");
        }
    }

    /**
     * 尝试在本帧启动一个技能；没有可用的就返回 false。
     *
     * <p><b>调用时机：</b>{@link #tick} 的「无技能」分支，每帧一次。</p>
     * <p><b>互斥性：</b>两个技能共用同一个 {@code p1Skill} 槽位，因此永远不可能同帧并行；
     * 两者同时就绪时用随机布尔二选一，避免总固定选到同一个技能造成节奏死板。</p>
     *
     * <p><b>为什么 BE 位移的就绪判据只看自身 CD：</b>早期这里还挂了一个
     * {@code !isBeDashDisabled()} 门槛，想做「一旦狂暴过就永久屏蔽 BE 走位」的单向锁。
     * 但那把锁根本没有消费者（{@code beDashDisabled} 字段已被删除），且语义与用户定稿的
     * 「狂暴期间禁用、回血恢复」相反 —— 定稿要的是<b>可逆</b>。可逆语义已由 {@link #tick}
     * 开头的 {@code isRageMode()} 早退天然保证：狂暴期间整段不走技能调度，BE 位移自然不参与；
     * 目标回血到 &gt; 50% 退出狂暴后调度恢复，BE 位移随 CD 就绪自动回到池中。
     * 因此这里不能再加第二个门禁条件（那会把「可逆」又变回「单向」）。</p>
     *
     * <p><b>副作用：</b>写 {@code p1Skill} 与该技能的时长；技能1 会调
     * {@link WitherBrain#staggerHeadShotCds} 重掷三颗头的冷却相位并写诊断日志，
     * 技能2 会调 {@link BeDash#begin} 初始化冲刺段。</p>
     *
     * @param brain 该凋灵的状态容器
     * @return true = 本帧成功启动了一个技能，调用方需在本帧就地执行它一次
     */
    private static boolean tryStartSkill(WitherBrain brain) {
        boolean s1Ready = brain.p1Skill1Cd() <= 0;
        // BE 走位就绪判据：**只看自身 CD**。
        // 【实机反馈修复】原先此处还有 `&& !brain.isBeDashDisabled()`，
        // 企图用「一旦狂暴过就永久屏蔽 BE 走位」的单向锁 —— 但那把锁
        // 早已被证明是死代码（无消费者），且语义也与用户定稿不符：
        // 用户要的是「狂暴期间禁用、回血恢复」（可逆），
        // 该语义由 tick() 开头的 `if (isRageMode()) { ... return; }` 天然保证 ——
        // 狂暴期间整段不走技能调度，BE 走位自然不参与；
        // 玩家回血到 >50% 退出狂暴后，调度恢复，BE 走位随 CD 就绪自动回到池中。
        // 因此这里不应再有第二个门禁条件（那会把"可逆"又变回"单向"）。
        boolean s2Ready = brain.p1Skill2Cd() <= 0;
        if (!s1Ready && !s2Ready) {
            return false;   // 都不可用 → 继续风筝常态
        }

        int pick;
        if (s1Ready && s2Ready) {
            pick = brain.random().nextBoolean() ? 1 : 2;   // 互斥：随机选一个
        } else {
            pick = s1Ready ? 1 : 2;
        }

        if (pick == 1) {
            brain.setP1Skill(1);
            brain.setP1Skill1Ticks(brain.randint(
                    WitherConfig.P1_BARRAGE_DURATION_MIN_TICKS,
                    WitherConfig.P1_BARRAGE_DURATION_MAX_TICKS));
            // 【实机反馈修复】三颗头的冷却在这里重新错峰。
            // 不重掷的话，进入技能那一帧三颗头的冷却可能同时为 0 → 当帧就是"三头齐射"；
            // 而技能窗口不长，随机相位还能把首发压到 0~1 个周期内，保证"立刻看得见"。
            brain.staggerHeadShotCds(WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                    WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
            brain.record("技能1弹幕压制");
            // 诊断日志：与冲撞/上升的既有做法一致，方便实测时直接翻 latest.log
            // 核对"技能有没有触发、这一轮的节奏参数是多少"，而不必靠肉眼推断。
            NewMod.LOGGER.info(
                    "[凋灵/诊断] 压制弹幕开始：持续={}tick 主头间隔={}tick 副头间隔={}~{}tick（三头各自独立计时）",
                    brain.p1Skill1Ticks(),
                    WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                    WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS,
                    WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
        } else {
            brain.setP1Skill(2);
            brain.setP1Skill2Ticks(WitherConfig.P1_BE_DASH_DURATION_TICKS);
            BeDash.begin(brain);
            brain.record("技能2BE走位");
        }
        return true;
    }

    // =================================================================
    //  攻击
    // =================================================================

    /**
     * 平A：消耗模式常态下由主头打出的单发凋灵之首。
     *
     * <p><b>调用时机：</b>{@link #tick} 的「风筝常态」分支末尾，每帧一次；
     * 是否真的发射由主头自身冷却节流。</p>
     *
     * <p><b>它占用的只是主头那一个冷却</b>（{@code headShotCd(0)}），与两颗副头互不影响。
     * 这一点正是历史 bug 的修复核心：早先平A 与压制弹幕<b>共用同一个计时器</b>，
     * 平A 的 40~80 tick 冷却会把技能窗口整段吃掉，
     * 于是出现「技能触发了却一帧都没发射」——症状表现为"一阶段不触发压制弹幕"。</p>
     *
     * <p><b>为什么伤害用 {@code IDLE_ATTACK_DAMAGE}（6.0）而不是 {@code SKULL_BLACK_DAMAGE}（8.0）：</b>
     * 让「平A 单发低伤」与「压制三头高伤」在伤害维度上也能分辨，
     * 而不只是靠射速与弹头数量区分两种攻击。</p>
     *
     * <p><b>副作用：</b>可能发射一枚凋灵之首并重掷主头冷却。
     * 无目标时直接返回且<b>不动冷却</b> —— 这样目标一出现立刻就能打，
     * 不用等待一个刚被消耗掉的冷却周期。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void basicAttack(WitherBrain brain) {
        if (brain.headShotCd(0) > 0) {
            return;
        }
        LivingEntity t = brain.targetEntity();
        if (t == null) {
            return;
        }
        if (WitherConfig.OUR_ATTACK_ENABLED) {
            // 平A 伤害用 IDLE_ATTACK_DAMAGE（6.0）而不是 SKULL_BLACK_DAMAGE（8.0）——
            // 让"平A 单发低伤"与"压制三头高伤"在伤害维度也可分辨，
            // 而不只是射速与弹头数量不同。
            SkullVolley.fire(brain.wither(), t, false, WitherConfig.IDLE_ATTACK_DAMAGE);
        }
        brain.setHeadShotCd(0, brain.randint(
                WitherConfig.P1_BASIC_ATTACK_INTERVAL_MIN_TICKS,
                WitherConfig.P1_BASIC_ATTACK_INTERVAL_MAX_TICKS));
    }

    /**
     * 技能1（压制弹幕）的三头独立射击。
     *
     * <p><b>调用时机：</b>{@link #tick} 中技能1 分支的每一帧（含触发帧）。</p>
     *
     * <p><b>节奏照抄原版 Java 凋灵：</b>主头用固定间隔
     * {@code BARRAGE_MAIN_SHOT_INTERVAL_TICKS}（对应原版
     * {@code RangedAttackGoal(this, 1.0, 40, 20.0f)} 里的 40），两颗副头各自
     * {@code 40 + rand(20)}。三颗头各读自己那一个冷却决定这一帧谁开火，因此天然错峰，
     * 不会出现"三个头一起发射"。</p>
     *
     * <p><b>副作用：</b>可能生成 0~3 枚凋灵之首并各自播一次开火音效；
     * 弹幕伤害取 {@code SKULL_BLACK_DAMAGE}（黑色弹幕的高伤值）。
     * 目标为 null（{@code targetEntity()} 未命中）时由 {@code fireReadyHeads} 内部跳过。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void fireBarrage(WitherBrain brain) {
        brain.fireReadyHeads(brain.targetEntity(),
                WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS,
                WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS,
                WitherConfig.SKULL_BLACK_DAMAGE);
    }

    /**
     * 狂暴模式的三头独立射击（常态攻击，不占用技能槽位）。
     *
     * <p><b>调用时机：</b>{@link #tick} 的狂暴分支，紧接 {@link #rushdown} 之后每帧调用。</p>
     *
     * <p><b>【实机反馈修复】</b>这里原先调的是三头同 tick 齐射的写法，玩家实测看到的就是
     * "残血追击变成三个头一起发射"。现在改为三颗头各自读冷却开火，节奏取自
     * {@code RAGE_*} 系列常量（比压制期更密，用来体现暴怒追杀的压力），但三头仍然错峰。</p>
     *
     * <p><b>副作用：</b>可能生成 0~3 枚凋灵之首并各播一次开火音效（伤害同 {@code SKULL_BLACK_DAMAGE}）。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void rageVolley(WitherBrain brain) {
        brain.fireReadyHeads(brain.targetEntity(),
                WitherConfig.RAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.RAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS,
                WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS,
                WitherConfig.SKULL_BLACK_DAMAGE);
    }

    /**
     * 狂暴模式的移动：直奔目标、直线贴脸到 1~2 格。
     *
     * <p><b>调用时机：</b>{@link #tick} 的狂暴分支第一句，每帧一次。</p>
     *
     * <p><b>与风筝走位的方向相反：</b>风筝是「维持距离」，这里是「消除距离」——
     * 直线冲过去、不绕圈也不拉扯。</p>
     *
     * <p><b>副作用：</b>登记本帧位移意图（{@code brain.moveBy}）或调用
     * {@code brain.stopMoving()} 表示本帧不动。无目标时直接返回（本帧零位移意图，
     * 被坐标所有权机制钉回本帧起点）。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    private static void rushdown(WitherBrain brain) {
        WitherBoss wither = brain.wither();
        LivingEntity t = brain.targetEntity();
        if (t == null) {
            return;
        }
        // 只用水平分量：飞行高度由 applyAltitude 统一维持，追杀阶段不该被目标的 y 带着上下起伏
        Vec3 toTarget = new Vec3(t.getX() - wither.getX(), 0.0D, t.getZ() - wither.getZ());
        double dist = toTarget.length();
        if (dist <= WitherConfig.P1_RUSHDOWN_DISTANCE) {
            brain.stopMoving();   // 已经贴脸，不再前进
            return;
        }
        Vec3 dir = toTarget.normalize();
        double speed = brain.randdouble(WitherConfig.P1_RUSHDOWN_SPEED_MIN,
                WitherConfig.P1_RUSHDOWN_SPEED_MAX);
        // 追杀也要处理撞墙：沿墙滑动绕过障碍
        if (!brain.canMove(dir, WitherConfig.P1_KITE_WALL_PROBE)) {
            Vec3 left = new Vec3(-dir.z, 0.0D, dir.x);
            Vec3 right = left.scale(-1.0D);
            if (brain.canMove(left, WitherConfig.P1_KITE_WALL_PROBE)) {
                dir = left;
            } else if (brain.canMove(right, WitherConfig.P1_KITE_WALL_PROBE)) {
                dir = right;
            }
        }
        brain.moveBy(dir, speed);
    }

    // =================================================================
    //  狂暴模式视觉标识
    // =================================================================

    /**
     * 狂暴模式的持续血红色粒子，让玩家一眼看出"它生气了"。
     *
     * <p><b>调用时机：</b>由 {@link WitherBrain#tick()} 在「一阶段且狂暴」时每帧调用
     * （见其 {@code if (rageMode) PhaseOneLogic.rageParticles(this)}）。</p>
     *
     * <p><b>实现方式：</b>用一个递减的冷却 {@code rageParticleCd} 节流，
     * 不是每帧都出粒子 —— 否则粒子密度高到看不出是"围绕身体的环"，还会白耗带宽。
     * 冷却到期就重设为一个完整间隔并让 {@link VisualFeedback#rageAura} 播一次。</p>
     *
     * <p><b>副作用：</b>写 {@code brain} 的狂暴粒子冷却；过期时生成服务端粒子（客户端据此显示）。</p>
     *
     * @param brain 该凋灵的状态容器
     */
    public static void rageParticles(WitherBrain brain) {
        int cd = brain.rageParticleCd() - 1;
        if (cd > 0) {
            brain.setRageParticleCd(cd);
            return;
        }
        brain.setRageParticleCd(WitherConfig.P1_RAGE_PARTICLE_INTERVAL_TICKS);
        VisualFeedback.rageAura(brain.wither(), brain.level());
    }
}

# Fusion Wither (Java + Bedrock)

把 Java 版的机制骨架与基岩版的攻击表现合并成一个重新设计的凋零 Boss：
一阶段是带双技能调度器的远程风筝战，半血进入狂暴后转为直线冲锋的压迫式进攻，
二阶段把凋零压到离地 2~3 格、以护甲弹开一切投射物，并在加权行为池里循环
「压制弹幕」与「冲撞」。

- 模组 id：`fusion_wither`
- 版本：1.0.0
- 作者：llxj12
- 许可：MIT（见 [LICENSE](LICENSE)）

## English Summary

**Fusion Wither** is a rework of the vanilla Wither for Minecraft 1.20.1 (Forge 47.x) that merges the
Java Edition fight loop with the Bedrock Edition attack set.

- **Phase 1 (100%–50% HP)** — ranged kiting at 6.5–8.5 blocks, driven by two independent skill timers:
  a staggered three-head barrage, and 1–2 high-speed Bedrock-style dashes (no shots during a dash).
- **Enrage (the moment HP drops below 50%)** — shorter shot intervals and a switch to straight-line charges.
- **Phase 2** — falls straight down, explodes 4 ticks after landing, then hovers 2–3 blocks above the ground,
  deflects *all* projectiles (melee and explosions still work), and picks between barrage and ground-slam
  from a weighted behaviour pool, with a low-pressure idle window when both are on cooldown.
- **Death sequence** — the lethal hit is rewritten into a real fatal blow so vanilla `die()` still runs
  (loot, XP and kill credit keep vanilla semantics); the model is stretched over 80 ticks while the
  death sound pitches down.

**Install** — put `fusion_wither-1.0.0.jar` into `mods/` on both client and server (client is needed only
to see the death animation). No extra dependencies.

**Config** — 43 tuning values are generated in `config/fusion_wither-common.toml` in five groups
(`wither` / `phase1` / `shots` / `phase2` / `misc`); `/reload` or a restart applies changes. Defaults are
the accepted game feel, so a fresh install behaves identically to the reviewed build.

**Debug** — `/wboss` (OP, permission level 2) prints or forces phases, behaviours, cooldowns, armour and HP.
Its feedback text is Chinese-only; nothing else in the mod is player-visible text.

Licensed under MIT; build with `./gradlew build --offline`.

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x（已在 47.4.22 与 47.4.28 上验证） |
| Java | 17 |
| 安装位置 | 客户端与服务端的 `mods/` 目录都需要放置 |

本模组不新增方块、物品或维度，只改造原版凋零的行为与表现，因此可以中途加入已有存档。
因为二阶段的死亡演出含客户端渲染逻辑（模型拉伸变宽），**只装服务端不会崩溃，但客户端看不到演出**。

## 玩法机制

**一阶段（血量 100% ~ 50%）**：远程风筝。
凋零维持 6.5~8.5 格的距离，行为由两个彼此独立的技能构成：

- **压制弹幕**：三头错峰齐射凋零之首，窗口内持续压制；
- **BE 位移**：1~2 段高速直线位移，位移期间不发射任何弹幕。

**狂暴（血量跌破 50% 的瞬间起）**：射击间隔整体缩短，语调与节奏切换为直线冲锋式进攻。

**二阶段**：压迫式近战。

- 入场先竖直下坠触地，停顿 4 tick 后爆炸开场；
- 常态压到离地 2~3 格，让玩家能够用近战够到；
- 护甲弹开**一切投射物**（弓、弩、三叉戟、雪球、火球……），近战与爆炸仍然有效；
- 加权行为池在「压制弹幕」与「冲撞」之间随机选择，两个行为都没有冷却时进入低压空窗期。

**死亡演出**：沿用原版 `die()` 的真实致命伤路线，因此下界之星、经验与击杀归属完全保持原版语义；
演出期间模型逐 tick 拉宽，并伴随音调下沉的持续哀嚎。

## 调试命令

`/wboss` 需要权限等级 2（OP）。所有子命令只影响调试，不影响正式玩法。

| 子命令 | 作用 |
| --- | --- |
| `/wboss dump` | 打印当前凋零的阶段、血量、行为池与冷却状态 |
| `/wboss diag` | 输出诊断信息（坐标、距离、目标、护甲状态） |
| `/wboss spawn` | 在场生成一只带完整激活动画的凋零 |
| `/wboss phase 1\|2` | 强制切换阶段 |
| `/wboss act <行为>` | 强制触发某个行为 |
| `/wboss cd` | 查看 / 重置技能冷却 |
| `/wboss armor on\|off` | 开关二阶段护甲 |
| `/wboss hp <数值>` | 设置血量 |
| `/wboss desire on\|off` | 开关高攻击欲望 |

命令反馈文本为中文，这是本模组唯一的非原版界面文本。

## 配置

首次启动会在 `config/fusion_wither-common.toml` 生成配置文件，分 5 组：

| 组 | 内容 |
| --- | --- |
| `wither` | 最大血量、二阶段阈值、出生苏醒时长 |
| `phase1` | 风筝距离、双技能冷却与窗口、BE 位移速度与段数 |
| `shots` | 黑/蓝凋零之首伤害、凋零效果时长、三头射击间隔 |
| `phase2` | 压制高度、行为池权重、冲撞伤害/速度/破坏方块、入场下坠 |
| `misc` | 回血速率、死亡演出时长与音效间隔、行为序列日志开关 |

改动后执行 `/reload` 或重启服务端生效。所有默认值就是已通过验收的那一版手感；
`debugLogBehaviorSequence` 默认关闭，仅验收时打开。

## 构建

```bash
./gradlew build --offline
```

产物：`build/libs/fusion_wither-1.0.0.jar`。
首次构建需要本地已预热 ForgeGradle 依赖（`GRADLE_USER_HOME`），全程不联网。

单元测试针对纯数值配置类，默认不执行（本机 Gradle 测试 worker 在中文路径下无法启动），
在正常环境用 `./gradlew test -PwithTests` 显式开启。

## 已知限制

- 只改造原版凋零，不改变凋零骷髅的生成上限与掉落表；
- 凋零护甲只免疫投射物，近战与爆炸按规格仍然生效，这是设计选择而非缺陷；
- `/wboss` 反馈文本为中文，未做多语言；
- 与其他改动凋零 AI 的模组同时安装时可能出现行为冲突（本模组接管了原版 `aiStep` 的水平位移）。

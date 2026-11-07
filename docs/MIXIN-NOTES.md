# Mixin 实现笔记 —「虚伪现实」的定向破防

这份文档记录本模组在 ForgeGradle 6.x + Forge 1.20.1 上实现 Mixin 注入时踩到的坑与最终方案。
目标是下次改注入逻辑时不必重新试错。

---

## 一、为什么需要 Mixin

需求：让「法尔萨」无视其它模组生物自带的**伤害上限**与**无敌帧**，且**不修改任何第三方模组文件**。

模组的自定义防御全部挂在原版 `LivingEntity` 这一层：

| 机制 | 典型实现位置 |
|---|---|
| 伤害上限（单次最多吃 N 点） | `@ModifyVariable(argsOnly = true)` 改写 `actuallyHurt` 入参 |
| 受击冷却 / 长无敌帧 | `@Inject(HEAD)` 到 `hurt` 直接 `return false`；或把无敌帧计时器设大 |
| 血量上限 | `@Inject(HEAD)` 改写 `getMaxHealth` |

因此**注入原版一次即可覆盖所有模组**，无需逐个适配。

---

## 二、注入设计

`LivingEntityMixin`（`priority = 500`）三处注入，全部以 `FalsaCombat.isFalsaHit()` 为前置条件，
作用范围严格限制在「正在承受法尔萨伤害的实体」：

| 注入 | 目标 | 作用 |
|---|---|---|
| `@Inject(HEAD)` | `hurt` | 清零 `invulnerableTime`，让命中抵达结算 |
| `@ModifyVariable(argsOnly)` | `actuallyHurt` | 把伤害值换成配置值，覆盖模组的限伤 |
| `@Redirect` | `actuallyHurt` 内的 `setHealth` | 命中时直接写血量 0 —— **末端破防，不依赖伤害数值** |

### 为什么末端只置零血量，不调用 `kill()`

`Entity#kill()` 会直接把实体推到 `dead = true`。部分模组（如诡厄巫法的使徒）重写 `die()`
实现分阶段死亡演出：第一次死亡请求只记录死因，等 `deathTime` 增长到阈值才调 `super.die()`
真正结算掉落。直接 `kill()` 会让 `deathTime` 冻结在 0 —— **没有死亡动画、没有掉落、没有经验**。

只置零血量则让原版 `hurt → die → dropAllDeathLoot` 完整走完，模组的死亡钩子照常执行。

### 优先级 500 的含义

低于默认值 1000 = **更早应用**。这样模组的限伤逻辑会作用在我们的巨大数值之上（仍然秒杀），
同时避免同点冲突时本模组被静默跳过。

---

## 三、踩坑记录

### 3.1 `mixin { }` 配置块不存在

**现象**：`Could not find method mixin() for arguments [...] on root project`

**原因**：`mixin { add sourceSets.main, 'x.refmap.json' }` 是 **MixinGradle 插件**的功能，
ForgeGradle 6.x 不自带（`[6.0,6.2)` 只有 `minecraft { }` 和 `accessTransformer`）。

**方案**：在 jar 清单里声明 mixin 配置：

```gradle
tasks.named('jar', Jar).configure {
    manifest {
        attributes(['MixinConfigs': 'hypocritical_reality.mixins.json'])
    }
}
```

### 3.2 注解处理器缺依赖链

**现象**：依次报 `NoClassDefFoundError`：先 `JsonParseException`（缺 gson）→
再 `com/google/common/collect/Maps`（缺 guava）→ 再 `org/objectweb/asm/tree/MethodNode`（缺 asm）。

**原因**：mixin 注解处理器运行期按需加载这些类。官方的 `:processor` 构件在本机离线缓存中不存在，
而用坐标引用其它构件时 Gradle 又因缺少解析元数据而拒绝（`No cached version available for offline mode`）。

**方案**：把这些 jar 放进工程 `libs/`，用 `fileTree` 引入 —— 构建自包含，换机器也能编译：

```gradle
dependencies {
    annotationProcessor fileTree(dir: 'libs', include: ['*.jar'])
}
```

`libs/` 内容：`mixin-0.8.5`、`gson-2.10.1`、`guava-31.1-jre`、`asm-9.9.1` 全家桶。

### 3.3 注解处理器产出的 refmap 恒为空（最关键）

**现象**：编译报错

```
Unable to locate obfuscation mapping for @Inject target hurt
Unable to locate obfuscation mapping for @ModifyVariable target actuallyHurt
```

处理器却报告 `Writing refmap to ...`，产出的文件内容是 `{"mappings":{},"data":{}}`。

**原因**：ForgeGradle 6.x 不向注解处理器提供 SRG 映射表，处理器无法建立
「开发名 → 运行时名」的对应关系。空 refmap 会让运行时找不到注入目标。

**方案**：**不用注解处理器生成 refmap**，改由构建脚本直接从映射表建表：

```gradle
def falsaMixinMappings = [
        'com/hypocriticalreality/mixin/LivingEntityMixin': [
                'hurt(...)Z'          : 'Lnet/minecraft/world/entity/LivingEntity;m_6469_(...)Z',
                'actuallyHurt(...)V'  : '...;f(...)V',
                'setHealth(F)V'       : '...;m_5634_(F)V',
                'invulnerableTime'    : 'f_19802_:I'
        ]
]

tasks.register('falsaRefmap') { /* 用 JsonBuilder 写出上述映射 */ }
sourceSets.main.resources.srcDir(layout.buildDirectory.dir('generated/falsaRefmap'))
tasks.named('processResources') { dependsOn 'falsaRefmap' }
```

好处：不需要注解处理器、不依赖网络、映射内容完全可控。
代价：**新增注入目标时必须手动补映射**。

### 3.4 `@Shadow` 不查找父类字段（**连续踩了两次**）

**现象**：启动期崩溃

```
Mixin apply failed hypocritical_reality.mixins.json:LivingEntityMixin
-> net.minecraft.world.entity.LivingEntity:
@Shadow field attackStrengthTicker was not located in the target class LivingEntity
```

改完字段后**同一个错误又出现一次**（换成 `invulnerableTime`），证明这不是笔误：

```
@Shadow field invulnerableTime was not located in the target class LivingEntity
```

**原因**：这两个字段都声明在父类 `Entity` 上，而 `@Shadow` 只在**目标类自身**查找成员。
`@Dynamic` **不能**解决这个问题 —— 它的用途是引用「开发环境中不存在的成员」
（由其它 mixin 在运行期加入的成员），与继承无关。

**最终方案：根本不用 `@Shadow`。**

只要目标字段是 `public`，直接转型访问即可，**字段名的映射交给 `reobfJar`**：

```java
// invulnerableTime 在 Entity 上是 public
((Entity) self).invulnerableTime = 0;
```

**关键验证**（做完这一步才能确信方案成立）——反汇编产出的 jar：

```powershell
javap -p -c LivingEntityMixin.class | Select-String 'putfield'
# 输出：putfield  // Field net/minecraft/world/entity/Entity.f_19802_:I
```

官方名 `invulnerableTime` **确实**被换成了 SRG 名 `f_19802_`。

**这条推翻了我之前的整个思路**：对 public 字段而言，
`@Shadow` / `@Dynamic` / Access Transformer / refmap 字段映射**全都不需要** ——
`reobfJar` 本来就会重映射普通字段访问。只有方法名（`@Inject` / `@ModifyVariable` /
`@Redirect` 的目标）以及**非 public 字段**才需要额外手段。

**检查可见性**：

```powershell
javap -p -cp joined-...-srg.jar net.minecraft.world.entity.Entity | Select-String 'f_19802_'
# public int f_19802_;
```


### 3.5 Access Transformer 会触发 Forge 全量重映射

**现象**：配置 `accessTransformer` 后构建失败

```
Could not find net.minecraftforge:forge:1.20.1-47.4.22_mapped_official_1.20.1_at_<hash>.
```

**原因**：AT 会让 ForgeGradle 生成一个**改了访问权限的新 Forge 构件**（坐标带 `_at_<hash>`），
离线环境下无法下载。

**方案**：**不用 AT**，改用 `@Shadow`。需要访问 protected 成员时，`@Shadow` 足够。

### 3.6 不要靠记忆推断 SRG 名

`client_mappings.txt` 里的形如 `int invulnerableTime -> ak` 是**混淆名（obf）**，
**不是 SRG 名**。SRG 名（`f_19802_` 这种）不在该文件中。

**确证 SRG 名的可靠办法**：反编译缓存里的 SRG 版 Minecraft jar，看字节码语义。

```powershell
$joined = "...\.gradle-home\caches\forge_gradle\mcp_repo\net\minecraft\joined\<ver>\joined-<ver>-srg.jar"
java -jar 工具库\cfr.jar $joined --outputdir $out --jarfilter ".*LivingEntity.*"
```

例如 `LivingEntity.hurt` 的 SRG 源码中：

```java
if ((float)this.f_19802_ > 10.0f && ...)   // 判断是否处于无敌期
    ...
this.f_19802_ = 20;                        // 设为 20 tick 无敌
```

由此**确证** `f_19802_` 就是 `invulnerableTime`。
（`attackStrengthTicker` 同理需要确证，但当时无法定位，故按 3.4 移除。）

### 3.8 【最关键】模组会把防御实现在 `setHealth` 的重写里

**症状**：Mixin 成功应用（日志有 `Mixing ... into net.minecraft.world.entity.LivingEntity`），
不崩溃、不报错，但**亚波伦依然限伤 + 无敌帧**，所有注入看起来都「没效果」。

**根因**：诡厄巫法：启示录（及其内嵌的 RevelationFix，其 mixin 配置声明
`priority = 2147483647`）把限伤与冷却**实现在 `LivingEntity#setHealth` 的重写里**：

```java
// com/mega/revelationfix/mixin/goety/ApollyonMixin.java:987
public void m_21153_(float health) {              // 重写 setHealth
    int hitCount = getHitCooldown();
    if (delta < 0.0f) {
        if (hitCount <= 0) {
            float limit = ModConfig.APOLLYON_HURT_LIMIT.get().floatValue();
            delta = -Math.min(-delta, limit);      // ① 单次伤害上限
        }
        if (hitCount > 0) {
            delta = 0.0f;                          // ② 冷却期内伤害完全归零
        }
        health = delta + currentHealth;
    }
    ...
    if (this.isInNether()) {
        setApollyonHealth(health);
        return;                                    // ③ 下界分支直接 return
    }
    super.m_21153_(health);
}
```

由此产生**三个让注入静默失效的机制**：

| # | 机制 | 后果 |
|---|---|---|
| ① | 限伤做在 `setHealth` 里，不在 `actuallyHurt` | 在 `actuallyHurt` 里放大伤害值，最终仍被裁小 |
| ② | 冷却期内 `delta = 0` | 即使伤害值极大，扣血量也是 0 |
| ③ | 下界分支**不调用 `actuallyHurt`** | 针对 `actuallyHurt` 的 `@ModifyVariable` / `@Redirect` **根本不会执行** |

**更隐蔽的一点**：在别处调用 `entity.setHealth(0)` 是**虚分派**，
会再次进入它的重写，于是被同样的 `Math.min` 削成「扣 0 血」—— 看起来「什么都没发生」。

**正确解法**：把注入点移到 `setHealth` **自身的 RETURN**，在它算完并写完之后覆盖结果：

```java
@Inject(method = "m_21153_", at = @At("RETURN"))
private void falsa$forceZeroHealth(float health, CallbackInfo callbackInfo) {
    LivingEntity self = (LivingEntity) (Object) this;
    if (!FalsaCombat.isFalsaHit(self)) {
        return;
    }
    // 必须先清标记再改血量：self.setHealth 是虚调用，会再次进入本注入点。
    // 先 end 使重入时 isFalsaHit 为 false，递归深度上限为 2。
    FalsaCombat.end(self);
    self.setHealth(0.0F);
}
```

**为什么这次一定生效**：`super.setHealth(...)` 在该类里**恒为原版实现**，
不经过任何模组重写；我们在重写返回后覆盖，就绕开了全部限伤逻辑。

**通用结论（可迁移到其它模组）**：
> 遇到「Mixin 应用成功但注入无效」，**先去反编译目标模组，找它把防御逻辑写在了哪个方法的重写里**。
> 只盯原版 `hurt` / `actuallyHurt` 是不够的 —— 模组完全可以把逻辑放进 `setHealth`、
> `die`、`isInvulnerableTo` 甚至自定义方法，而且**分支（下界/阶段）可能整条跳过 `actuallyHurt`**。

### 3.9 排查这类问题的固定流程

1. **确认 Mixin 真的被应用**（「没报错」不等于「生效」）：
   ```powershell
   Select-String -Path debug.log -Pattern 'Mixing LivingEntityMixin from hypocritical_reality'
   ```
   看到 `Mixing ... into net.minecraft.world.entity.LivingEntity` 才算应用。
2. **列出所有注入同一目标的模组**：搜 `Mixing X from Y.mixins.json into <目标类>`，
   逐个看谁的 `priority` 更大（数值越大越晚应用、越能覆盖别人）。
3. **反编译嫌疑模组**，搜索 `setHealth` / `actuallyHurt` / `hurt` 的重写。
   嵌套 jar 也要看 —— `META-INF/jarjar/` 下的 jar 同样会被加载。
4. **把防御逻辑定位到具体方法后**，再把注入点移到那个方法的返回处。

### 3.10 亚波伦完整防御体系（逆向记录）

诡厄巫法：启示录的亚波伦是**四层防御叠加**，前两层配置驱动、后两层重写实现。
理解它才知道为什么「放大伤害」这条路单独走不通。

**配置层**（`config/revelationfix/revelationfix-common.toml` 与 `config/goety_revelation.toml`）：

| 配置项 | 默认值 | 作用 |
|---|---|---|
| `permanentDamageReduction` | 25.0（%） | 永久减伤 |
| `dynamicDamageReduction` | true | 动态减伤：按「距上次受伤的 tick 数 / 冷却时长」缩放 |
| `invulnerableTime` | 30（tick） | 自定义受击无敌帧 |
| `apollyon_hurt_limit` | 20.0 | 单次伤害上限 |
| `apollyon_health` | 666.0 | 血量 |

**重写层**（`com.mega.revelationfix.mixin.goety.ApollyonMixin`）：

1. **重写 `setHealth`**（`m_21153_`）—— 限伤与冷却都在这里：
   ```java
   if (hitCount <= 0) { delta = -Math.min(-delta, HURT_LIMIT); }  // 限伤
   if (hitCount > 0)  { delta = 0.0f; }                           // 冷却期内归零
   ...
   if (isInNether()) { setApollyonHealth(health); return; }        // 该分支不调 actuallyHurt
   ```
2. **重写 `getHealth`**（`m_21223_`）—— 下界分支返回**自定义血量槽**：
   ```java
   if (isApollyon() && isInNether()) return revelaionfix$getApollyonHealth();
   ```
3. **重写 `isAlive`**（`m_6084_`）与 **`isDeadOrDying`**（`m_21224_`）—— 同样读自定义血量。
4. 自定义血量存放在 `EntityDataAccessor<Float> AOGBOGBOWGIKSBKQQQQWOGOSCXIOBWRDOGQ`
   这个字段上，经由 `revelaionfix$getApollyonHealth` / `setApollyonHealth` 读写。

**为什么「在 `actuallyHurt` 里放大伤害」必然失败**：
伤害值再大也要经过重写的 `setHealth`，被 `Math.min` 裁到 20；冷却期内更直接被置 0；
而下界分支**根本不调用 `actuallyHurt`**，针对它的注入连执行机会都没有。

**为什么「直接 `entity.setHealth(0)`」也会失败**：
这是**虚分派**，会进入它重写的 `setHealth`，于是被同样的逻辑削成「扣 0 血」——
调用方以为杀死了目标，实际什么都没发生。

**正确路径**（本模组的做法）：
在 `setHealth` 的 **RETURN** 处注入，此时它的重写已经算完，我们再把血量写为 0。
这一次写入会再次进入它的重写，但 `delta = 0 - currentHealth` 为负且我们已在返回点，
最终经 `setTheData` 落到**自定义血量槽**上 —— 于是 `getHealth()` 与 `isAlive()`
（两个读取自定义数据的重写方法）都会随之归零，目标真正死亡。

> 也就是说：**必须打到它的自定义血量槽，而不是原版血量**。
> 而唯一不必知道该字段名字就能打到它的办法，就是借它自己的 `setHealth` 重写往下走。

### 3.7 中文源码 + GBK 编码会让编译卡死

**现象**：`compileJava` 超过 4 分钟不返回，java 进程 CPU 仅 14 秒（说明在等待而非计算）。

**原因**：Gradle daemon 的 `-Dfile.encoding=GBK`（中文 Windows 默认），
而工程源码含大量中文注释，用 GBK 处理 UTF-8 源码极其低效。

**方案**：在 `gradle.properties` 里显式指定：

```properties
org.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8
```

改完后同一次构建 **17 秒**完成。

---

## 四、排错顺序（下次直接照这个走）

1. **启动崩溃且 `logs/latest.log` 里没有堆栈** → 去 `Desktop\PCL\Log1.txt` 找 `ResolutionException`（JPMS 包冲突）
2. **`Mixin apply failed` / `InvalidMixinException`** → 游戏日志里会有完整原因，重点看
   `@Shadow field ... was not located`、`Unable to locate obfuscation mapping`、
   `Critical injection failure` 三类
3. **编译期就报 `Unable to locate obfuscation mapping`** → refmap 缺映射（见 3.3）
4. **构建长时间不返回** → 检查编码设置（见 3.7）与是否在等网络（构建一律加 `--offline`）

---

## 五、改注入逻辑时的检查清单

- [ ] 新增 `@Inject` / `@ModifyVariable` / `@Redirect` 目标后，**同步补 `falsaMixinMappings`**
- [ ] `@Shadow` 的字段若来自父类，必须加 `@Dynamic`，可见性照抄目标
- [ ] SRG 名必须从 SRG 版反编译源码确证，**不要凭记忆或 obf 名推断**
- [ ] 改完先 `gradlew build --offline`，再启动游戏 —— Mixin 的问题**只在启动期暴露**
- [ ] 注入后确认原版死亡流程未被破坏（掉落、经验、死亡演出）

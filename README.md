<div align="center">


# 随记 · FloatNote

**任何时候随手一点，记录转瞬即逝的灵感。**

[![License: MIT](https://img.shields.io/badge/License-MIT-2e2e2e.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Kotlin-Compose%20%2B%20View-7f52ff?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Release: v2.0](https://img.shields.io/badge/Release-v2.0-2e2e2e.svg)]()

一个 Android 悬浮笔记应用：平时只在屏幕边缘留一根半透明竖条，点一下，笔记窗口原位浮现——写完收起，回到刚才的事。**不打断，是它存在的全部理由。**

</div>

---

## 交互模型



**贴边竖条 ↔ 悬浮笔记窗**，两形态互斥、位置互相衔接（长条中心 = 竖条中心）：

| 操作 | 动作 |
|---|---|
| 点竖条 | 笔记窗**原位浮现** |
| 点长条手柄 | 整窗轻移淡出，竖条原位归来 |
| 按住长条拖动 | 移动窗口位置 |
| 双指捏合 / 拖角 | 调整窗口大小 |
| 工具栏 ⇄ 键 | 整窗翻到屏幕另一侧，左右布局全部镜像，单手通吃 |

## 功能亮点

**悬浮窗**
- 贴边竖条收起态：约 30% 透明度（可调），几乎不占注意力
- 键盘避让：窗口在屏幕下半时唤起键盘自动浮到键盘上方，收起键盘自动回落
- 键盘优先：未聚焦时第一次点正文只弹键盘，再点才定位光标
- 三档质感（标准 / 高光 / 柔光）、颜色 / 透明度 / 圆角 / 厚度 / 长度全参数化，实时生效
- 开机自启（可选）：重启后悬浮窗自动回来

**主界面**
- 笔记列表：搜索（标题 + 正文）、置顶、长按操作
- 编辑器：有序 / 无序列表、待办、时间戳，回车自动续点
- 回收站：删除先进回收站保留 30 天，可恢复 / 彻底删除 / 清空
- 备份：导出全部笔记为纯文本、从文件导入（自动跳过重复）

**数据**
- 自动保存（1 秒防抖），数据只存本地 Room 数据库，无任何网络请求


## 下载

从 [Releases](../../releases) 页面获取 APK 安装包。安装后：

1. 打开应用，点顶栏「悬浮窗」
2. 按系统引导授予"显示在其他应用上层"权限

也可以自行构建（见下）。欢迎 [反馈问题](../../issues)。

## 构建

```bash
git clone <本仓库>
cd FloatNote
gradlew assembleDebug      # Debug 包
gradlew assembleRelease    # Release 包（沿项目惯例使用 debug 签名，发布请自行配置签名）
gradlew testDebugUnitTest  # 单元测试
```

要求 JDK 17+、Android SDK（compileSdk 35，minSdk 26）。产物在 `app/build/outputs/apk/`。

## 技术架构

- **Kotlin** · 单 Activity + 单前台服务（specialUse）持有全部 overlay 窗口
- **Jetpack Compose**（主界面列表 / 编辑 / 设置）+ **传统 View**（悬浮窗——overlay 场景 View ）
- **Room**（数据库 v3 ）
- **DataStore Preferences**（全部设置，驱动实时生效）· Coroutines/Flow
- 触摸逻辑全部在 View 内自处理，Service 只管生命周期与设置分发

核心源码导航见 [AGENTS.md](AGENTS.md)（含悬浮窗渲染 / 动画 / IME 避让等平台坑位全记录）。


## 许可

[MIT](LICENSE) —— 可自由使用、修改、分发，请保留版权声明。

## 致谢

本项目由 AI 结对开发（设计、架构、编码、测试全程人机协作），交互细节经过真机反复打磨。

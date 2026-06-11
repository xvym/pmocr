# pmocr

基于 Java 8、Maven 的《宝可梦 金/银》日文像素文字离线 OCR。程序由用户圈选模拟器游戏画面，自动定位标准对话框，等待文字加载稳定后再输出日文。

## 为什么使用 matrix

运行时使用 `src/main/resources/matrix/pokemon_gs_font_1bpp.bin`，不使用 `src/main/resources/font` 下的截图：

- 游戏文字本身是固定的 `8x8` 点阵，矩阵可以直接做汉明距离匹配，速度快且结果可解释。
- `matrix` 没有边框、相邻文字粘连和截图缩放污染；`font` 图片需要额外清洗，反而会降低准确率。
- 129 个矩阵模板在程序启动时一次性读入内存，识别过程中不访问磁盘。
- 不需要 OpenCV、Tesseract、网络服务或训练模型，打包后可以完全离线运行。

日文浊音和半浊音不需要补充几十张新模板。游戏实际把基础假名和上方的 `゛` / `゜` 分开绘制；程序分别识别后使用 Unicode NFC 组合，例如 `か + ゛ -> が`、`ホ + ゜ -> ポ`。拨音 `ん` 已包含在矩阵中。游戏复用同一像素图形表示 `り` 和 `リ`，程序会根据相邻平假名/片假名做上下文消歧。

## 环境

- JDK 8
- Maven 3.x
- Windows、Linux 或 macOS 图形桌面

项目没有第三方运行时依赖。首次 Maven 构建是否需要联网只取决于本机是否已有 Maven 默认插件缓存；生成的 JAR 运行时完全离线。

## 构建与启动

```powershell
mvn clean package
java -jar target\pmocr-1.0.0.jar
```

项目使用 `maven-shade-plugin` 在 `package` 阶段生成可执行 JAR；以后如果加入第三方依赖，会一并打进 `target/pmocr-1.0.0.jar`。shade 插件同时会保留一个未合并依赖前的 `target/original-pmocr-1.0.0.jar`。

资源目录采用 Maven 默认结构：

- `src/main/resources/matrix`：运行时 OCR 字体矩阵。
- `src/main/resources/font`：字体截图资料，当前不参与运行时识别。
- `src/main/resources/text/text.xlsx`：内置新版翻译文本库。
- `src/main/resources/text/text_clean.xlsx`：内置旧版备用翻译文本库。
- `src/test/resources/testpic`：离线回归截图和标注。
- `src/test/resources/badcase`：问题样本截图。

操作步骤：

1. 启动模拟器并进入游戏。
2. 点击“圈选游戏区域”，拖动圈选完整游戏画面或完整对话框。不要只圈文字本身，因为程序需要边框来定位。
3. 点击“开始实时识别”。
4. 对话文字停止变化约 `350ms` 后，程序自动识别并显示日文。
5. 点击“复制文字”可将当前结果复制到剪贴板。

建议模拟器使用整数倍率和最近邻缩放，并关闭 CRT、扫描线等着色器。识别器也支持常见的非整数和平滑缩放，但无滤镜的像素画面最可靠。程序窗口默认置顶，请不要让它遮住被圈选的文字区域。

## 离线图片验证

识别单张截图：

```powershell
java -jar target\pmocr-1.0.0.jar --image src\test\resources\testpic\1.bmp
```

使用同名 `.txt` 标注批量验证：

```powershell
java -jar target\pmocr-1.0.0.jar --verify src\test\resources\testpic
```

当前 `src/test/resources/testpic` 中四张原始截图全部逐字匹配，模板平均汉明距离均为 `0.00`。额外测试的对话框裁剪图、`2x`、`3x`、`2.5x` 双线性和高质量双三次缩放图也全部匹配。

Windows 旧版终端若显示日文乱码，可先执行 `chcp 65001`；这只影响终端显示，不影响识别结果和 GUI。

## 翻译文本库

程序启动时会把 JAR 内置翻译文本库预加载到内存。查找顺序如下：

1. JAR 内置的 `text/text.xlsx`
2. JAR 内置的 `text/text_clean.xlsx`

新版 `text.xlsx` 会读取 `文1` 到 `文10` 的对话文本、`图` 的图鉴文本，以及其他名词 sheet。固定文本进入 `HashMap`，识别后为 O(1) 查找；含 `<PLAYER>`、`<RIVAL>`、`【0】` 等占位符的文本会在启动时预编译为模板，固定文本未命中时再进行模板匹配。模板捕获到的动态值会先查宝可梦、道具、招式、地点等名词表，能翻译则替换为中文，查不到则保留原文。

命令行可直接测试翻译：

```powershell
java -jar target\pmocr-1.0.0.jar --translate "ゴールドは　ウツギはかせ　から\nマスターボールを　もらった！"
```

## 实现方式

实时处理流程：

1. 每 `80ms` 截取一次用户圈选区域。
2. 搜索标准对话框的长水平边框，根据边框长度推算缩放倍率和文字网格原点。
3. 对文字区域生成轻量二值指纹，持续检测内容是否仍在变化。
4. 指纹稳定 `350ms` 且不同于上次输出后，才执行完整 OCR。
5. 对两行、每行 18 个 `8x8` 字符格采样，使用汉明距离匹配内存中的 129 个字体矩阵。
6. 单独读取基础字符上方三行，检测 `゛` / `゜` 并组合为实际日文。

核心代码：

- `PokemonOcr`：对话框检测、缩放采样、模板匹配和日文组合。
- `FontTemplates`：启动时加载并持有全部字体矩阵。
- `StabilityDetector`：检测对话文字是否加载完成。
- `RegionSelector` / `RealtimeRecognizer`：屏幕圈选和实时截图循环。

## 适用范围与限制

- 面向日文版《宝可梦 金/银》的标准双行对话框。
- 菜单、战斗 HUD、拉丁字母文本、其他语言版本以及修改过字体的 ROM 不在当前识别范围内。
- `り` / `リ` 的像素完全相同，只能使用上下文推断；孤立出现时默认输出 `リ`。
- 严重遮挡边框、旋转画面或强烈图像着色器会导致无法定位或匹配。

## 字符资料

实现和字符处理参考了以下资料：

- [pret/pokegold disassembly](https://github.com/pret/pokegold)
- [Generation II character encoding](https://bulbapedia.bulbagarden.net/wiki/Character_encoding_in_Generation_II)
- [Generation II text entry](https://bulbapedia.bulbagarden.net/wiki/Text_entry_%28Generation_II%29)

现有矩阵已经包含本功能所需的基础平假名、片假名、促音、小假名、拨音以及独立浊点/半浊点，因此没有引入来源不明或与游戏像素风格不一致的新字体图片。

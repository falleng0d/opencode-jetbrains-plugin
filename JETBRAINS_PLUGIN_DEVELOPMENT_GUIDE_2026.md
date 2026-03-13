# Complete Guide to JetBrains IDE Plugin Development (2026)

## Table of Contents

1. [Introduction](#introduction)
2. [Prerequisites](#prerequisites)
3. [Getting Started](#getting-started)
4. [Plugin Architecture](#plugin-architecture)
5. [Core Concepts](#core-concepts)
6. [Plugin Configuration](#plugin-configuration)
7. [Action System](#action-system)
8. [Services](#services)
9. [Extension Points](#extension-points)
10. [PSI (Program Structure Interface)](#psi-program-structure-interface)
11. [Virtual File System](#virtual-file-system)
12. [Notifications and UI](#notifications-and-ui)
13. [Settings and Configuration](#settings-and-configuration)
14. [Testing](#testing)
15. [Plugin Dependencies](#plugin-dependencies)
16. [Plugin Signing and Publishing](#plugin-signing-and-publishing)
17. [Performance and UX Best Practices](#performance-and-ux-best-practices)
18. [Advanced Topics](#advanced-topics)
19. [Troubleshooting](#troubleshooting)
20. [Resources](#resources)

---

## Introduction

JetBrains IDE plugins extend the functionality of IntelliJ-based IDEs like IntelliJ IDEA, PyCharm, WebStorm, GoLand, and more. This comprehensive guide covers everything you need to know to create professional-grade plugins in 2026.

### Why Create JetBrains Plugins?

- Extend IDE functionality with custom features
- Support new programming languages
- Integrate external tools and services
- Automate repetitive development tasks
- Share solutions with the developer community

---

## Prerequisites

### Required Software

1. **IntelliJ IDEA** (Ultimate or Community Edition)
   - Download from: https://www.jetbrains.com/idea/download/
   - Use the latest version for best plugin development support

2. **Java Development Kit (JDK)**
   - **JDK 21+** for targeting IDE 2024.2+
   - **JDK 17+** for targeting IDE 2022.3+
   - Download from: https://adoptium.net/ or your preferred JDK distributor

3. **Gradle 8.13+**
   - Usually bundled with IntelliJ IDEA
   - Or install from: https://gradle.org/install/

### Required IntelliJ IDEA Plugins

Install these plugins in IntelliJ IDEA:

1. **Plugin DevKit** (Required)
   - Install from: https://plugins.jetbrains.com/plugin/22851-plugin-devkit
   - Not bundled since version 2023.3
   - Provides essential plugin development tools

2. **Gradle** (Usually bundled)
   - Required for building and managing dependencies

### Knowledge Requirements

- Basic Java or Kotlin programming
- Understanding of build systems (Gradle)
- Familiarity with IntelliJ IDEA features

---

## Getting Started

### Option 1: Using IntelliJ Platform Plugin Template (Recommended)

The [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) is the quickest way to start:

1. Visit https://github.com/JetBrains/intellij-platform-plugin-template
2. Click **"Use this template"** button
3. Name your repository
4. Clone the generated repository
5. Open in IntelliJ IDEA

**Advantages:**

- Pre-configured Gradle build
- GitHub Actions CI/CD setup
- Best practices included
- Ready-to-use project structure

### Option 2: Using New Project Wizard

1. Open IntelliJ IDEA
2. Go to **File → New → Project**
3. Select **"IDE Plugin"** from the left panel
4. Configure:
   - **Name**: Your plugin name
   - **Location**: Project directory
   - **Type**: Plugin
   - **Group**: Your domain (e.g., `com.example`)
   - **Artifact**: Plugin artifact name
   - **JDK**: Select JDK 21+ or 17+
5. Click **Create**

### Project Structure

```
my-plugin/
├── .run/                          # Run configurations
│   └── Run IDE with Plugin.run.xml
├── gradle/
│   └── wrapper/                   # Gradle wrapper files
├── src/
│   ├── main/
│   │   ├── kotlin/               # Kotlin source files (or java/)
│   │   └── resources/
│   │       └── META-INF/
│   │           ├── plugin.xml    # Plugin configuration file
│   │           └── pluginIcon.svg # Plugin icon
│   └── test/                     # Test sources
├── build.gradle.kts              # Gradle build script
├── gradle.properties             # Gradle properties
├── settings.gradle.kts           # Gradle settings
└── .gitignore
```

---

## Plugin Architecture

### Plugin Types

1. **Application-level**: Single instance for entire IDE
2. **Project-level**: One instance per project
3. **Module-level**: One instance per module (avoid if possible)

### Plugin Distribution Structure

#### Simple Plugin (Single JAR)

```
my-plugin.jar
├── com/
│   └── example/
│       └── MyPlugin.class
└── META-INF/
    ├── plugin.xml
    └── pluginIcon.svg
```

#### Plugin with Dependencies

```
my-plugin/
├── lib/
│   ├── dependency1.jar
│   └── dependency2.jar
└── my-plugin.jar
    ├── com/
    │   └── example/
    │       └── MyPlugin.class
    └── META-INF/
        ├── plugin.xml
        └── pluginIcon.svg
```

---

## Core Concepts

### 1. Components (Deprecated)

⚠️ **Note**: Components are deprecated since 2020.1. Use **Services** instead.

### 2. Services

Services are the modern way to encapsulate plugin logic. They are loaded on demand and cached.

#### Application-level Service

```kotlin
@Service
class MyAppService {
    fun doSomething() {
        // Implementation
    }
}

// Usage
val service = service<MyAppService>()
```

#### Project-level Service

```kotlin
@Service(Service.Level.PROJECT)
class MyProjectService(private val project: Project) {
    fun doSomething() {
        val projectName = project.name
        // Implementation
    }
}

// Usage
val service = project.service<MyProjectService>()
```

### 3. Extensions

Extensions allow plugins to extend platform functionality:

```xml
<extensions defaultExtensionNs="com.intellij">
    <applicationService
        serviceImplementation="com.example.MyService"/>
    <annotator
        language="JAVA"
        implementationClass="com.example.MyAnnotator"/>
</extensions>
```

### 4. Listeners

Listen to platform events:

```xml
<applicationListeners>
    <listener
        topic="com.intellij.ide.AppLifecycleListener"
        class="com.example.MyListener"/>
</applicationListeners>
```

---

## Plugin Configuration

### The plugin.xml File

The `plugin.xml` file is the heart of your plugin configuration:

```xml
<idea-plugin>
    <!-- Plugin identification -->
    <id>com.example.myplugin</id>
    <name>My Plugin</name>
    <version>1.0.0</version>

    <!-- Vendor information -->
    <vendor
        url="https://www.example.com"
        email="support@example.com">
        Example Company
    </vendor>

    <!-- Description -->
    <description><![CDATA[
        Provides support for My Framework.
        <ul>
            <li>Code completion</li>
            <li>Navigation</li>
            <li>Refactoring</li>
        </ul>
    ]]></description>

    <!-- Change notes -->
    <change-notes><![CDATA[
        <h2>Version 1.0.0</h2>
        <ul>
            <li>Initial release</li>
        </ul>
    ]]></change-notes>

    <!-- Compatibility -->
    <idea-version since-build="241.0"/>

    <!-- Dependencies -->
    <depends>com.intellij.modules.platform</depends>
    <depends optional="true"
             config-file="my-plugin-java.xml">
        com.intellij.java
    </depends>

    <!-- Extensions -->
    <extensions defaultExtensionNs="com.intellij">
        <!-- Extension declarations -->
    </extensions>

    <!-- Actions -->
    <actions>
        <!-- Action declarations -->
    </actions>

    <!-- Listeners -->
    <applicationListeners>
        <!-- Listener declarations -->
    </applicationListeners>
</idea-plugin>
```

### Key Configuration Elements

#### Plugin ID

```xml
<id>com.example.myplugin</id>
```

- Must be unique across all plugins
- Use fully qualified name (like Java packages)
- Cannot be changed after publishing

#### Plugin Name

```xml
<name>My Plugin</name>
```

- User-visible display name
- Use Title Case

#### Version

```xml
<version>1.0.0</version>
```

- Follow semantic versioning (MAJOR.MINOR.PATCH)

#### Compatibility

```xml
<idea-version since-build="241.0"/>
```

- `since-build`: Minimum compatible IDE version
- `until-build`: Maximum compatible IDE version (optional, recommended to omit)

---

## Action System

Actions are the primary way to add menu items, toolbar buttons, and keyboard shortcuts.

### Creating an Action

#### 1. Action Implementation

```kotlin
class MyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT  // Background thread
    }

    override fun update(e: AnActionEvent) {
        // Enable/disable action based on context
        val project = e.project
        e.presentation.isEnabled = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        // Execute when action is triggered
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // Your action logic here
        Notifications.Bus.notify(
            Notification(
                "My Plugin",
                "Action Executed",
                "My action was executed successfully!",
                NotificationType.INFORMATION
            ),
            project
        )
    }
}
```

#### 2. Register Action in plugin.xml

```xml
<actions>
    <!-- Register action -->
    <action id="com.example.MyAction"
            class="com.example.MyAction"
            text="My Action"
            description="Execute my custom action"
            icon="AllIcons.Actions.Execute">

        <!-- Add to Tools menu -->
        <add-to-group group-id="ToolsMenu" anchor="first"/>

        <!-- Keyboard shortcut -->
        <keyboard-shortcut
            keymap="$default"
            first-keystroke="control alt M"/>

        <!-- Alternative text for different contexts -->
        <override-text place="EditorPopup" text="Execute My Action"/>

        <!-- Synonyms for search -->
        <synonym text="Execute Action"/>
    </action>

    <!-- Create action group -->
    <group id="com.example.MyGroup"
           text="My Plugin"
           description="My plugin actions"
           popup="true"
           icon="AllIcons.Nodes.Plugin">

        <action id="com.example.Action1"
                class="com.example.Action1"
                text="Action 1"/>

        <separator/>

        <action id="com.example.Action2"
                class="com.example.Action2"
                text="Action 2"/>

        <!-- Add group to main menu -->
        <add-to-group group-id="MainMenu"
                      anchor="after"
                      relative-to-action="ToolsMenu"/>
    </group>
</actions>
```

### Action Best Practices

1. **Use Background Thread (BGT)** for `update()` when possible:

   ```kotlin
   override fun getActionUpdateThread() = ActionUpdateThread.BGT
   ```

2. **Keep `update()` fast** - no heavy operations

3. **Don't store state in action fields** - actions are singletons

4. **Use `DumbAwareAction`** for actions available during indexing:
   ```kotlin
   class MyDumbAwareAction : DumbAwareAction() {
       // ...
   }
   ```

### Common Action Groups

- `MainMenu` - Main menu bar
- `ToolsMenu` - Tools menu
- `EditorPopupMenu` - Editor right-click menu
- `ProjectViewPopupMenu` - Project view right-click menu
- `VcsGroups` - Version control menus

---

## Services

Services encapsulate business logic and are loaded on demand.

### Light Services (Recommended)

Light services don't require plugin.xml registration:

```kotlin
// Application-level light service
@Service
class MyAppService {
    fun processData(data: String): String {
        return data.uppercase()
    }
}

// Project-level light service
@Service(Service.Level.PROJECT)
class MyProjectService(private val project: Project) {
    fun getProjectName(): String = project.name
}
```

### Traditional Services

Register in plugin.xml:

```xml
<extensions defaultExtensionNs="com.intellij">
    <applicationService
        serviceInterface="com.example.MyService"
        serviceImplementation="com.example.MyServiceImpl"/>

    <projectService
        serviceInterface="com.example.MyProjectService"
        serviceImplementation="com.example.MyProjectServiceImpl"/>
</extensions>
```

### Retrieving Services

```kotlin
// Application service
val appService = service<MyAppService>()

// Project service
val projectService = project.service<MyProjectService>()

// Java equivalent
MyAppService appService = ApplicationManager
    .getApplication()
    .getService(MyAppService.class);
```

### Service with State Persistence

```kotlin
@Service
class MySettingsService : PersistentStateComponent<MySettingsService.State> {

    private var state = State()

    data class State(
        var enabled: Boolean = true,
        var threshold: Int = 100
    )

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): MySettingsService = service()
    }
}
```

---

## Extension Points

Extension points allow other plugins to extend your plugin.

### Declaring Extension Points

```xml
<extensionPoints>
    <!-- Interface extension point -->
    <extensionPoint
        name="myExtensionPoint"
        interface="com.example.MyInterface"/>

    <!-- Bean class extension point -->
    <extensionPoint
        name="myBeanExtension"
        beanClass="com.example.MyBeanClass"/>
</extensionPoints>
```

### Using Extension Points

```kotlin
class MyExtensionUser {
    companion object {
        private val EP_NAME = ExtensionPointName.create<MyBeanClass>(
            "com.example.myplugin.myBeanExtension"
        )
    }

    fun processExtensions() {
        EP_NAME.extensionList.forEach { extension ->
            // Process each extension
            println("Extension: ${extension.key}")
        }
    }
}
```

### Contributing to Extension Points

```xml
<extensions defaultExtensionNs="com.intellij">
    <fileType
        name="My File Type"
        implementationClass="com.example.MyFileType"
        extensions="myext"/>
</extensions>
```

---

## PSI (Program Structure Interface)

PSI provides the syntactic and semantic code model for files.

### PSI Files

```kotlin
// Get PSI file from virtual file
val psiFile = PsiManager.getInstance(project).findFile(virtualFile)

// Get PSI file from document
val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)

// Get current PSI file in editor
val psiFile = e.getData(CommonDataKeys.PSI_FILE)
```

### PSI Elements

```kotlin
// Navigate PSI tree
val file: PsiFile = // ...
val firstChild: PsiElement? = file.firstChild
val children: Array<PsiElement> = file.children

// Find element at offset
val element = file.findElementAt(editor.caretModel.offset)

// Get parent
val parent = element?.parent

// Get element type
val elementType = element?.node?.elementType
```

### Modifying PSI

```kotlin
// Write action is required for modifications
WriteCommandAction.runWriteCommandAction(project) {
    // Create new element
    val factory = PsiElementFactory.getInstance(project)
    val newMethod = factory.createMethod("newMethod", PsiType.VOID)

    // Add element
    psiClass.add(newMethod)

    // Delete element
    element?.delete()

    // Replace element
    element?.replace(newElement)
}
```

### PSI Performance

- Use `PsiElement.getUseScope()` to check relevance
- Avoid deep PSI traversal in UI threads
- Use stubs for indexed access when possible

---

## Virtual File System

The Virtual File System (VFS) provides a unified API for file operations.

### Getting Virtual Files

```kotlin
// From path
val file = LocalFileSystem.getInstance().findFileByPath("/path/to/file")

// From PSI file
val virtualFile = psiFile.virtualFile

// From editor
val virtualFile = FileDocumentManager.getInstance().getFile(document)

// Get project base directory
val baseDir = project.baseDir
```

### File Operations

```kotlin
// Read file content
val content = virtualFile?.contentsToByteArray()

// Write to file (requires write action)
WriteCommandAction.runWriteCommandAction(project) {
    virtualFile?.setBinaryContent(newContent)
}

// List directory contents
val children = virtualFile?.children

// Find child by name
val child = virtualFile?.findChild("filename.txt")

// Refresh file system
virtualFile?.refresh(false, true)
```

### Listening to VFS Changes

```kotlin
// Register listener in plugin.xml
<applicationListeners>
    <listener
        topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"
        class="com.example.MyFileListener"/>
</applicationListenersListeners>

// Implementation
class MyFileListener : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        events.forEach { event ->
            when (event) {
                is VFileCreateEvent -> println("File created: ${event.path}")
                is VFileDeleteEvent -> println("File deleted: ${event.path}")
                is VFileContentChangeEvent -> println("File changed: ${event.path}")
            }
        }
    }
}
```

---

## Notifications and UI

### Top-Level Notifications (Balloons)

```kotlin
// Register notification group in plugin.xml
<extensions defaultExtensionNs="com.intellij">
    <notificationGroup
        id="My Plugin Notifications"
        displayType="BALLOON"/>
</extensions>

// Show notification
val notification = NotificationGroupManager.getInstance()
    .getNotificationGroup("My Plugin Notifications")
    .createNotification(
        "Title",
        "Content",
        NotificationType.INFORMATION
    )

// Add action to notification
notification.addAction(object : NotificationAction("Action") {
    override fun actionPerformed(
        e: AnActionEvent,
        notification: Notification
    ) {
        // Handle action
        notification.expire()
    }
})

// Show notification
notification.notify(project)
```

### Editor Hints

```kotlin
// Show hint in editor
HintManager.getInstance().showErrorHint(
    editor,
    "Error message",
    editor.caretModel.offset,
    editor.caretModel.offset + 1,
    HintManager.ABOVE,
    HintManager.HIDE_BY_ANY_KEY,
    0
)
```

### Dialogs

```kotlin
class MyDialog(project: Project) : DialogWrapper(project) {
    private val textField = JBTextField()

    init {
        title = "My Dialog"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Input:") {
                cell(textField)
                    .focused()
            }
        }
    }

    override fun doOKAction() {
        val input = textField.text
        // Process input
        super.doOKAction()
    }
}

// Show dialog
val dialog = MyDialog(project)
if (dialog.showAndGet()) {
    // User clicked OK
}
```

### Popups

```kotlin
// Create popup
val popup = JBPopupFactory.getInstance()
    .createListPopup(
        object : BaseListPopupStep<String>("Select Item", listOf("A", "B", "C")) {
            override fun onChosen(selectedValue: String?, finalChoice: Boolean): PopupStep<*>? {
                // Handle selection
                return PopupStep.FINAL_CHOICE
            }
        }
    )

// Show popup
popup.showInBestPositionFor(e.dataContext)
```

---

## Settings and Configuration

### Creating Settings

#### 1. Settings Class

```kotlin
class MySettings : PersistentStateComponent<MySettings.State> {

    private var state = State()

    data class State(
        var enabled: Boolean = true,
        var maxItems: Int = 100,
        var pattern: String = ""
    )

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var maxItems: Int
        get() = state.maxItems
        set(value) { state.maxItems = value }

    companion object {
        fun getInstance(): MySettings = service()
    }
}
```

#### 2. Settings UI (Configurable)

```kotlin
class MySettingsConfigurable : Configurable {

    private var panel: MySettingsPanel? = null

    override fun getDisplayName(): String = "My Plugin"

    override fun createComponent(): JComponent {
        panel = MySettingsPanel()
        return panel!!.createPanel()
    }

    override fun isModified(): Boolean {
        val settings = MySettings.getInstance()
        return panel!!.enabled != settings.enabled ||
               panel!!.maxItems != settings.maxItems
    }

    override fun apply() {
        val settings = MySettings.getInstance()
        settings.enabled = panel!!.enabled
        settings.maxItems = panel!!.maxItems
    }

    override fun reset() {
        val settings = MySettings.getInstance()
        panel!!.enabled = settings.enabled
        panel!!.maxItems = settings.maxItems
    }
}
```

#### 3. Register in plugin.xml

```xml
<extensions defaultExtensionNs="com.intellij">
    <applicationConfigurable
        parentId="tools"
        instance="com.example.MySettingsConfigurable"
        id="com.example.MySettingsConfigurable"
        displayName="My Plugin"/>
</extensions>
```

### Using Kotlin UI DSL

```kotlin
fun createPanel(): DialogPanel {
    return panel {
        group("General Settings") {
            row {
                checkBox("Enable feature", ::enabled)
            }
            row("Max items:") {
                intTextField(::maxItems, 0..1000)
                    .comment("Maximum number of items to display")
            }
            row("Pattern:") {
                textField(::pattern)
                    .comment("Regular expression pattern")
            }
        }
    }
}
```

---

## Testing

### Test Setup

```kotlin
// Add test framework dependency in build.gradle.kts
dependencies {
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
    }
}

// Create test class
class MyPluginTest : LightPlatformCodeInsightTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testMyFeature() {
        // Configure test file
        myFixture.configureByText("test.java", """
            class Test {
                public void test() {
                    <caret>
                }
            }
        """.trimIndent())

        // Perform action
        myFixture.performEditorAction("com.example.MyAction")

        // Verify result
        myFixture.checkResult("""
            class Test {
                public void test() {
                    // Action executed
                }
            }
        """.trimIndent())
    }
}
```

### Testing Highlighting

```kotlin
fun testHighlighting() {
    myFixture.configureByText("test.java", """
        class Test {
            int x = <error descr="Undefined variable">undefined</error>;
        }
    """.trimIndent())

    myFixture.checkHighlighting()
}
```

### Testing Completion

```kotlin
fun testCompletion() {
    myFixture.configureByText("test.java", """
        class Test {
            void test() {
                Sys<caret>
            }
        }
    """.trimIndent())

    val completions = myFixture.completeBasic()

    assertTrue(completions.any { it.lookupString == "System" })
}
```

---

## Plugin Dependencies

### Adding Plugin Dependencies

#### 1. Locate Plugin ID

For JetBrains plugins, find the ID on the plugin page in JetBrains Marketplace.

For bundled plugins, use the `printBundledPlugins` Gradle task:

```bash
./gradlew printBundledPlugins
```

Common bundled plugin IDs:

- Java: `com.intellij.java`
- Kotlin: `org.jetbrains.kotlin`
- Gradle: `com.intellij.gradle`
- Maven: `org.jetbrains.idea.maven`
- Database: `com.intellij.database`
- Terminal: `org.jetbrains.plugins.terminal`

#### 2. Add to Gradle (build.gradle.kts)

```kotlin
dependencies {
    intellijPlatform {
        create("IC", "2024.3")

        // Bundled plugin
        bundledPlugin("com.intellij.java")

        // Plugin from marketplace
        plugin("org.intellij.scala", "2024.1.4")
    }
}
```

#### 3. Declare in plugin.xml

```xml
<!-- Required dependency -->
<depends>com.intellij.java</depends>

<!-- Optional dependency -->
<depends optional="true"
         config-file="my-plugin-kotlin.xml">
    org.jetbrains.kotlin
</depends>
```

### Module Dependencies

```xml
<!-- Platform (required for all plugins) -->
<depends>com.intellij.modules.platform</depends>

<!-- Language support -->
<depends>com.intellij.modules.lang</depends>

<!-- Specific functionality -->
<depends>com.intellij.modules.vcs</depends>
<depends>com.intellij.modules.xdebugger</depends>
```

---

## Plugin Signing and Publishing

### Plugin Signing

#### 1. Generate Private Key

```bash
# Generate encrypted private key
openssl genpkey \
    -aes-256-cbc \
    -algorithm RSA \
    -out private_encrypted.pem \
    -pkeyopt rsa_keygen_bits:4096

# Convert to RSA format
openssl rsa \
    -in private_encrypted.pem \
    -out private.pem

# Generate certificate chain
openssl req \
    -key private.pem \
    -new \
    -x509 \
    -days 365 \
    -out chain.crt
```

#### 2. Configure Signing in build.gradle.kts

```kotlin
intellijPlatform {
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}
```

#### 3. Build Signed Plugin

```bash
./gradlew signPlugin
```

### Publishing to JetBrains Marketplace

#### 1. Create JetBrains Account

1. Visit https://account.jetbrains.com
2. Click "Create Account"
3. Fill in the form and register

#### 2. Get Personal Access Token

1. Visit https://plugins.jetbrains.com/author/me/tokens
2. Click "Generate Token"
3. Copy the token (shown only once!)

#### 3. Configure Publishing in build.gradle.kts

```kotlin
intellijPlatform {
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
        // Or use environment variable
        // token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
```

#### 4. First Upload (Manual)

1. Build plugin: `./gradlew buildPlugin`
2. Visit https://plugins.jetbrains.com/author/me
3. Click "Add new plugin"
4. Upload the ZIP file from `build/distributions/`

#### 5. Subsequent Uploads (Gradle)

```bash
# Set environment variable
export ORG_GRADLE_PROJECT_intellijPlatformPublishingToken='YOUR_TOKEN'

# Or pass as parameter
./gradlew publishPlugin -PintellijPlatformPublishingToken=YOUR_TOKEN
```

### Release Channels

```kotlin
intellijPlatform {
    publishing {
        channels = listOf("beta")  // or "alpha", "eap", etc.
    }
}
```

Users can add custom repositories:

- Alpha: `https://plugins.jetbrains.com/plugins/alpha/list`
- Beta: `https://plugins.jetbrains.com/plugins/beta/list`
- EAP: `https://plugins.jetbrains.com/plugins/eap/list`

---

## Performance and UX Best Practices

### General UX Guidelines

1. **Ease of Use**
   - Features should work out-of-the-box
   - Default settings should be sensible
   - Actions should be easy to find

2. **Stability**
   - Implement comprehensive tests
   - Handle errors gracefully
   - Set up error reporting

3. **Performance**
   - Keep `update()` methods fast
   - Use background threads for heavy operations
   - Optimize PSI queries

4. **Distribution Size**
   - Minimize dependencies
   - Optimize assets
   - Use on-demand downloads for large resources

### Performance Tips

#### Threading Model

```kotlin
// UI operations must run on EDT
ApplicationManager.getApplication().invokeLater {
    // Update UI
}

// Heavy operations should run on background thread
ApplicationManager.getApplication().executeOnPooledThread {
    // Heavy computation
}
```

#### Read/Write Actions

```kotlin
// Read action (can run in parallel)
ApplicationManager.getApplication().runReadAction {
    // Read PSI, VFS, etc.
}

// Write action (exclusive)
WriteCommandAction.runWriteCommandAction(project) {
    // Modify PSI, VFS, etc.
}
```

#### Avoiding UI Freezes

- Never perform I/O or heavy computations on EDT
- Use `ProgressManager` for long-running operations
- Cache results when appropriate

### Plugin Description Best Practices

- Clear, concise description
- Use HTML for formatting
- Include screenshots or demos
- List key features
- Provide documentation links

---

## Advanced Topics

### Kotlin Coroutines

```kotlin
@Service
class MyCoroutineService(
    private val scope: CoroutineScope
) {
    fun startAsyncTask() {
        scope.launch {
            // Coroutine running in service scope
            val result = withContext(Dispatchers.IO) {
                // I/O operation
            }
            withContext(Dispatchers.EDT) {
                // Update UI
            }
        }
    }
}
```

### Multi-Module Projects

For large plugins, split code into modules:

#### Root module (build.gradle.kts)

```kotlin
plugins {
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3")
    }
}
```

#### Submodule (build.gradle.kts)

```kotlin
plugins {
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3")
    }
}
```

### Dynamic Plugins

Support plugin loading/unloading without restart:

```xml
<idea-plugin require-restart="false">
    <!-- ... -->
</idea-plugin>
```

Requirements:

- All extension points must be dynamic
- No static state
- Proper cleanup in `dispose()`

### Custom Language Support

To add support for a new language:

1. Define file type
2. Implement lexer and parser
3. Create PSI elements
4. Add syntax highlighting
5. Implement code completion
6. Add navigation and refactoring
7. Support inspections and intentions

See: https://plugins.jetbrains.com/docs/intellij/custom-language-support.html

---

## Troubleshooting

### Common Issues

#### 1. Plugin Not Loading

**Symptoms:** Plugin doesn't appear in IDE

**Solutions:**

- Check plugin.xml syntax
- Verify dependencies are declared
- Check IDE compatibility (since-build)
- Look for errors in IDEA log

#### 2. ClassNotFoundException

**Symptoms:** Runtime class not found errors

**Solutions:**

- Add missing dependencies to build.gradle.kts
- Declare dependencies in plugin.xml
- Check for conflicting library versions

#### 3. PSI Operations Fail

**Symptoms:** PSI queries return null or throw exceptions

**Solutions:**

- Wrap in read action
- Check for disposed project
- Verify file type support
- Use proper thread (BGT vs EDT)

#### 4. Slow Performance

**Symptoms:** UI freezes, slow operations

**Solutions:**

- Profile with YourKit or JProfiler
- Check for EDT violations
- Optimize PSI queries
- Use caching appropriately

#### 5. Plugin Verifier Errors

**Symptoms:** Compatibility check fails

**Solutions:**

```bash
./gradlew runPluginVerifier
```

- Fix reported API incompatibilities
- Update since-build version
- Add missing dependencies

### Debugging

#### Enable Internal Mode

1. Help → Edit Custom VM Options
2. Add: `-Didea.is.internal=true`
3. Restart IDE
4. Tools → Internal Actions

#### Logging

```kotlin
private val LOG = Logger.getInstance(MyClass::class.java)

LOG.info("Information message")
LOG.warn("Warning message")
LOG.error("Error message", exception)
```

#### Plugin Verifier

```bash
# Run verifier
./gradlew runPluginVerifier

# Check specific IDE
./gradlew runPluginVerifier -PideVersions=2024.1,2024.2
```

---

## Resources

### Official Documentation

- **IntelliJ Platform SDK Docs**: https://plugins.jetbrains.com/docs/intellij/
- **JetBrains Marketplace**: https://plugins.jetbrains.com
- **Marketplace Docs**: https://plugins.jetbrains.com/docs/marketplace/

### Code Samples

- **Official Samples**: https://github.com/JetBrains/intellij-sdk-code-samples
- **Plugin Template**: https://github.com/JetBrains/intellij-platform-plugin-template

### Tools

- **Plugin DevKit**: https://plugins.jetbrains.com/plugin/22851-plugin-devkit
- **Plugin Verifier**: https://github.com/JetBrains/intellij-plugin-verifier
- **Marketplace ZIP Signer**: https://github.com/JetBrains/marketplace-zip-signer

### Community

- **Platform Developers Slack**: https://plugins.jetbrains.com/slack
- **GitHub Issues**: https://github.com/JetBrains/intellij-community/issues
- **Forums**: https://intellij-support.jetbrains.com/

### Webinars and Videos

- **Busy Plugin Developers Series**: Search on YouTube
- **IntelliJ Platform YouTube**: https://www.youtube.com/user/JetBrainsTV

### Blogs

- **JetBrains Platform Blog**: https://blog.jetbrains.com/platform/
- **IntelliJ IDEA Blog**: https://blog.jetbrains.com/idea/

---

## Quick Reference

### Essential Gradle Tasks

```bash
# Build plugin
./gradlew buildPlugin

# Run IDE with plugin
./gradlew runIde

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew runPluginVerifier

# Sign plugin
./gradlew signPlugin

# Publish plugin
./gradlew publishPlugin

# List bundled plugins
./gradlew printBundledPlugins
```

### Essential APIs

```kotlin
// Get current project
val project = e.project

// Get current editor
val editor = e.getData(CommonDataKeys.EDITOR)

// Get current file
val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

// Get PSI file
val psiFile = e.getData(CommonDataKeys.PSI_FILE)

// Show notification
NotificationGroupManager.getInstance()
    .getNotificationGroup("My Group")
    .createNotification("Title", "Content", NotificationType.INFORMATION)
    .notify(project)

// Execute write action
WriteCommandAction.runWriteCommandAction(project) {
    // Modify PSI
}

// Execute on background thread
ApplicationManager.getApplication().executeOnPooledThread {
    // Heavy operation
}

// Execute on EDT
ApplicationManager.getApplication().invokeLater {
    // Update UI
}
```

### Plugin.xml Essentials

```xml
<idea-plugin>
    <id>com.example.myplugin</id>
    <name>My Plugin</name>
    <version>1.0.0</version>
    <vendor>Example</vendor>
    <idea-version since-build="241.0"/>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <applicationService
            serviceImplementation="com.example.MyService"/>
    </extensions>

    <actions>
        <action id="com.example.MyAction"
                class="com.example.MyAction"
                text="My Action">
            <add-to-group group-id="ToolsMenu"/>
        </action>
    </actions>
</idea-plugin>
```

---

## Conclusion

This guide covers everything you need to create professional JetBrains IDE plugins in 2026. Remember to:

1. **Start with the plugin template** for best practices
2. **Use services** instead of deprecated components
3. **Follow the threading model** to avoid UI freezes
4. **Test thoroughly** with the built-in testing framework
5. **Sign your plugins** before publishing
6. **Monitor performance** and optimize where needed
7. **Document your plugin** with clear descriptions

The IntelliJ Platform is powerful and flexible. With this guide and the official documentation, you have everything needed to create amazing plugins that enhance developer productivity.

Good luck with your plugin development journey!

---

**Last Updated:** 2026
**IntelliJ Platform Version:** 2024.3+
**Gradle Plugin Version:** 2.x

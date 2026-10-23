param(
    [string]$DistributionPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'LocalChat'),
    [switch]$SkipDeploy,
    [switch]$SkipNativeQA
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskJdk = if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME/bin/javac.exe")) { $env:JAVA_HOME } else {
    $taskJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($taskJavac) { Split-Path (Split-Path $taskJavac.Source -Parent) -Parent } else {
        $taskLocalJdk = Get-ChildItem -LiteralPath "$PSScriptRoot/.tools" -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } | Select-Object -First 1
        if ($taskLocalJdk) { $taskLocalJdk.FullName }
    }
}
if (!$taskJdk) { throw 'JDK 17+ required. Set JAVA_HOME or place a portable JDK in .tools.' }
$taskLibrary = Join-Path $PSScriptRoot 'lib/flatlaf-3.7.2.jar'
$taskHashStream = [IO.File]::OpenRead($taskLibrary)
$taskSha256 = [Security.Cryptography.SHA256]::Create()
try { $taskLibraryHash = [BitConverter]::ToString($taskSha256.ComputeHash($taskHashStream)).Replace('-', '') }
finally { $taskSha256.Dispose(); $taskHashStream.Dispose() }
if ($taskLibraryHash -ne '917AFF3963C88D797D0FD9B9CCBD70F7681C101DF9D11C59E2BC7A3A6C0FABF4') { throw 'FlatLaf checksum mismatch' }
$taskPdfPython = Join-Path $PSScriptRoot 'pdfcontrol/runtime/python/python.exe'
if (!(Test-Path -LiteralPath $taskPdfPython)) { throw 'PDFCONTROL runtime missing. Restore the local engine/runtime in pdfcontrol.' }
& $taskPdfPython -I "$PSScriptRoot/integration/test_sync_engine.py"
if ($LASTEXITCODE) { throw 'Owned engine build isolation tests failed' }
& $taskPdfPython -I "$PSScriptRoot/integration/test_import_audit.py"
if ($LASTEXITCODE) { throw 'Local import/catalogue/drawing audit tests failed' }
& $taskPdfPython -I "$PSScriptRoot/integration/test_catalogue_manager.py"
if ($LASTEXITCODE) { throw 'Catalogue import/rollback/upgrade preservation tests failed' }
& $taskPdfPython -I "$PSScriptRoot/integration/test_catalogue_update.py"
if ($LASTEXITCODE) { throw 'Factory catalogue update tests failed' }
& $taskPdfPython -I "$PSScriptRoot/integration/sync_engine.py" --package "$PSScriptRoot/pdfcontrol"
if ($LASTEXITCODE) { throw 'PDFCONTROL engine synchronization failed' }
$taskBuild = Join-Path $PSScriptRoot 'build'
# Resolve and verify before removing generated build output.
$taskResolved = [IO.Path]::GetFullPath($taskBuild)
if ($taskResolved -ne ([IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\build')) { throw 'Unexpected build directory' }
if (Test-Path -LiteralPath $taskResolved) { Remove-Item -LiteralPath $taskResolved -Recurse -Force }
$taskNativeQaOptions = @(if ($SkipNativeQA) { '-Dlocalchat.skipNativeQA=true' })
if ($SkipNativeQA) { Write-Output 'Native desktop QA explicitly skipped; report this limitation with the build.' }
$taskClasses = Join-Path $taskBuild 'classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskSources = (Get-ChildItem -LiteralPath "$PSScriptRoot/src/localchat" -Filter '*.java').FullName
& "$taskJdk/bin/javac.exe" -encoding UTF-8 --release 17 -cp $taskLibrary -d $taskClasses $taskSources
if ($LASTEXITCODE) { throw 'Compilation failed' }
Copy-Item -LiteralPath "$PSScriptRoot/src/localchat/i18n" -Destination "$taskClasses/localchat/i18n" -Recurse
Copy-Item -LiteralPath "$PSScriptRoot/VERSION" -Destination $taskClasses
$taskTests = (Get-ChildItem -LiteralPath "$PSScriptRoot/tests" -Filter '*.java').FullName
& "$taskJdk/bin/javac.exe" -encoding UTF-8 --release 17 -cp "$taskClasses;$taskLibrary" -d "$taskBuild/tests" $taskTests
if ($LASTEXITCODE) { throw 'Test compilation failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/import-audit-home" "-Daudit.python=$taskPdfPython" "-Daudit.package=$PSScriptRoot/pdfcontrol" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ImportAuditTest
if ($LASTEXITCODE) { throw 'Offline audit permissions/integration tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/compaction-budget-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.CompactionBudgetTest
if ($LASTEXITCODE) { throw 'Compaction time budget tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/compaction-resume-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.CompactionResumeTest
if ($LASTEXITCODE) { throw 'Compaction checkpoint recovery tests failed' }
foreach($taskNewTest in @('AutomaticDelegationTest','InlineApprovalTest','InlineScrollTest')){
    & "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/$taskNewTest-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" "localchat.$taskNewTest"
    if ($LASTEXITCODE) { throw "Failed: $taskNewTest" }
}
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/catalogue-manager-home" "-Daudit.python=$taskPdfPython" "-Daudit.package=$PSScriptRoot/pdfcontrol" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.CatalogueManagerTest
if ($LASTEXITCODE) { throw 'Catalogue manager UI/service tests failed' }
& "$taskJdk/bin/java.exe" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.SettingsStoreTest
if ($LASTEXITCODE) { throw 'Settings persistence tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/concurrent-chat-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ConcurrentChatsTest
if ($LASTEXITCODE) { throw 'Concurrent chat/session/metadata tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.UiTest
if ($LASTEXITCODE) { throw 'Tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.FeatureTest
if ($LASTEXITCODE) { throw 'Feature tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/provider-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ProviderTest
if ($LASTEXITCODE) { throw 'Provider/metadata tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/provider-management-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ProviderManagementTest
if ($LASTEXITCODE) { throw 'Provider management regression tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/provider-setup-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ProviderSetupTest
if ($LASTEXITCODE) { throw 'Provider creation, endpoint scope and xAI authentication tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/project-location-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ProjectLocationsTest
if ($LASTEXITCODE) { throw 'Factory/project location preflight tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/hotfolder-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.HotfolderTest
if ($LASTEXITCODE) { throw 'Hotfolder/status/notification tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/hotfolder-reuse-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.HotfolderReuseTest
if ($LASTEXITCODE) { throw 'Hotfolder project reuse/email preparation tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/composer-assist-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ComposerAssistTest
if ($LASTEXITCODE) { throw 'Composer interaction regression tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/interaction-cards-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.InteractionCardsTest
if ($LASTEXITCODE) { throw 'Compact inline decision/suggestion tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/interaction-recovery-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.InteractionRecoveryTest
if ($LASTEXITCODE) { throw 'Invalid interaction recovery tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/compact-cards-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.CompactCardsTest
if ($LASTEXITCODE) { throw 'Compact interaction card layout tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/queue-notification-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.QueueNotificationsTest
if ($LASTEXITCODE) { throw 'Queue/local monthly notification/drop tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/tool-protocol-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ToolProtocolTest
if ($LASTEXITCODE) { throw 'Provider native tool protocol repair tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/catalog-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ModelCatalogTest
if ($LASTEXITCODE) { throw 'Catalog/probe tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/model-suitability-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ModelSuitabilityTest
if ($LASTEXITCODE) { throw 'Profile model suitability tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/work-progress-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.WorkProgressTest
if ($LASTEXITCODE) { throw 'Continuity/loop/provider isolation tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/discovery-recovery-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.DiscoveryRecoveryTest
if ($LASTEXITCODE) { throw 'Repeated discovery recovery tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/recovery-final-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.RecoveryFinalTest
if ($LASTEXITCODE) { throw 'Recovery final visibility and budget tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/sidebar-navigation-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.SidebarNavigationTest
if ($LASTEXITCODE) { throw 'Sidebar filter and click coalescing tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/skill-progress-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.SkillProgressTest
if ($LASTEXITCODE) { throw 'Skill resource/progress regression tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/copy-resume-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.CopyResumeTest
if ($LASTEXITCODE) { throw 'Verified resumable copy tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/agent-group-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.AgentGroupTest
if ($LASTEXITCODE) { throw 'Runtime parallel agent tests failed' }
& "$taskJdk/bin/java.exe" "-Dlocalchat.home=$taskBuild/agent-artifacts-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.AgentArtifactsTest
if ($LASTEXITCODE) { throw 'Verified child-agent PDF artifact tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/agent-integration-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.AgentIntegrationTest
if ($LASTEXITCODE) { throw 'Chat to runtime-agent integration tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/status-activity-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.StatusActivityTest
if ($LASTEXITCODE) { throw 'Status colour/agent activity tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/agent-panel-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.AgentPanelTest
if ($LASTEXITCODE) { throw 'Agent side-panel regression tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/attachment-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.AttachmentTest
if ($LASTEXITCODE) { throw 'Attachment/drop/session tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" @taskNativeQaOptions '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/composer-shortcuts-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ComposerShortcutsTest
if ($LASTEXITCODE) { throw 'Composer keyboard/Undo/Redo tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/contextguardtest-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ContextGuardTest
if ($LASTEXITCODE) { throw 'Context budget/automatic tool-loop compaction tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/commandpalettetest-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.CommandPaletteTest
if ($LASTEXITCODE) { throw 'Themed slash command palette tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/chat-display-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ChatDisplayTest
if ($LASTEXITCODE) { throw 'Plain chat display/original data tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/favorites-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ModelFavoritesTest
if ($LASTEXITCODE) { throw 'Model favorite tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/presentation-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ChatPresentationTest
if ($LASTEXITCODE) { throw 'Markdown and inline PDF presentation tests failed' }
& "$taskJdk/bin/java.exe" @taskNativeQaOptions '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/navigation-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ChatNavigationTest
if ($LASTEXITCODE) { throw 'Chat navigation/disclosure tests failed' }
& "$taskJdk/bin/java.exe" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.PdfResultPathTest
if ($LASTEXITCODE) { throw 'PDF result path diagnostics tests failed' }
& "$taskJdk/bin/java.exe" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.MemoryReducerTest
if ($LASTEXITCODE) { throw 'Adaptive memory reduction tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/inference-retry-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.InferenceRetryTest
if ($LASTEXITCODE) { throw 'Inference gateway recovery tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/pdf-preview-test-home" "-Dpdfpreview.python=$taskPdfPython" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.PdfPreviewTest
if ($LASTEXITCODE) { throw 'PDF card and right preview tests failed' }
& "$taskJdk/bin/java.exe" @taskNativeQaOptions '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/context-popup-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ContextUsagePopupTest
if ($LASTEXITCODE) { throw 'Live context popup tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/composer-split-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.ComposerSplitTest
if ($LASTEXITCODE) { throw 'Composer resize tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/international-chat-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.InternationalChatTest
if ($LASTEXITCODE) { throw 'International chat tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/local-memories-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.LocalMemoriesTest
if ($LASTEXITCODE) { throw 'Local memory tests failed' }
& "$taskJdk/bin/java.exe" '-Dlocalchat.test=true' "-Dlocalchat.home=$taskBuild/normal-mode-test-home" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.NormalModeTest
if ($LASTEXITCODE) { throw 'Clean Normal mode isolation tests failed' }
& $taskPdfPython -I "$PSScriptRoot/integration/test_owned_layout.py" --package "$PSScriptRoot/pdfcontrol"
if ($LASTEXITCODE) { throw 'Owned engine layout tests failed' }
& $taskPdfPython -I "$PSScriptRoot/integration/test_pdfcontrol_bridge.py" --package "$PSScriptRoot/pdfcontrol" --output "$taskBuild/pdfcontrol-tests"
if ($LASTEXITCODE) { throw 'PDFCONTROL workflow tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" "-Dpdfcontrol.package=$PSScriptRoot/pdfcontrol" "-Dpdfcontrol.projects=$taskBuild/pdfcontrol-tests/PROJECTS" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.PdfControlTest
if ($LASTEXITCODE) { throw 'Direct PDFCONTROL UI/MCP tests failed; distribution was not changed' }
& "$taskJdk/bin/java.exe" "-Dpdfcontrol.package=$PSScriptRoot/pdfcontrol" "-Dpdfcontrol.projects=$taskBuild/pdfcontrol-tests/PROJECTS" -cp "$taskClasses;$taskBuild/tests;$taskLibrary" localchat.PdfAgentTest
if ($LASTEXITCODE) { throw 'Model to PDFCONTROL workflow tests failed' }
$taskJar = Join-Path $taskBuild 'LocalChat.jar'
$taskManifest = Join-Path $taskBuild 'MANIFEST.MF'
@"
Manifest-Version: 1.0
Main-Class: localchat.LocalChat
Class-Path: lib/flatlaf-3.7.2.jar

"@ | Set-Content -LiteralPath $taskManifest -Encoding ascii
& "$taskJdk/bin/jar.exe" cfm $taskJar $taskManifest -C $taskClasses .
if ($LASTEXITCODE) { throw 'JAR creation failed' }
New-Item -ItemType Directory -Path "$taskBuild/lib" -Force | Out-Null
Copy-Item -LiteralPath $taskLibrary -Destination "$taskBuild/lib"
if ($SkipDeploy) { Write-Output "Tested JAR: $taskJar"; exit 0 }
if (!(Test-Path -LiteralPath $DistributionPath)) { New-Item -ItemType Directory -Path $DistributionPath | Out-Null }
New-Item -ItemType Directory -Path "$DistributionPath/lib" -Force | Out-Null
Copy-Item -LiteralPath $taskLibrary -Destination "$DistributionPath/lib" -Force
Copy-Item -LiteralPath "$PSScriptRoot/THIRD_PARTY.md" -Destination $DistributionPath -Force
# Never copy settings, conversations, rules or overwrite user-edited MCP configuration.
Copy-Item -LiteralPath $taskJar -Destination (Join-Path $DistributionPath 'LocalChat.jar') -Force
Copy-Item -LiteralPath "$PSScriptRoot/packaging/Porneste LocalChat.bat" -Destination $DistributionPath -Force
Copy-Item -LiteralPath "$PSScriptRoot/README.md" -Destination (Join-Path $DistributionPath 'CITESTE.md') -Force
Copy-Item -LiteralPath "$PSScriptRoot/VERSION","$PSScriptRoot/CHANGELOG.md" -Destination $DistributionPath -Force
Copy-Item -LiteralPath "$PSScriptRoot/integration/pdfcontrol_bridge.py" -Destination "$PSScriptRoot/pdfcontrol/octop_mcp.py" -Force
New-Item -ItemType Directory -Path "$DistributionPath/skills/pdfcontrol-octop" -Force | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/integration/PDFCONTROL_SKILL.md" -Destination "$DistributionPath/skills/pdfcontrol-octop/SKILL.md" -Force
if (!(Test-Path -LiteralPath (Join-Path $DistributionPath 'unelte.json'))) {
    Copy-Item -LiteralPath "$PSScriptRoot/packaging/unelte.json" -Destination $DistributionPath
}
if (Test-Path -LiteralPath "$PSScriptRoot/packaging/skills") {
    & robocopy.exe "$PSScriptRoot/packaging/skills" "$DistributionPath/skills" /E /XO /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'Skill copy failed' }
}
if (Test-Path -LiteralPath "$PSScriptRoot/pdfcontrol/octop_mcp.py") {
    & robocopy.exe "$PSScriptRoot/pdfcontrol" "$DistributionPath/pdfcontrol" /E /XO /NFL /NDL /NJH /NJS /NP /XD data_lokaal .git .github __pycache__ test-tmp checked PDF_NOU OUD NIEUW pdfcompare _meetscripts Octop-PDFCONTROL pdfcontrol-octop /XF test_*.py *.backup-* octop_mcp_trace.jsonl installatie.log .gitignore skill-octop.zip | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'PDFControl copy failed' }
}
# Engine content must match the tested snapshot even when legacy file dates are older.
function Get-EngineDigest([string]$Path) {
    $taskDigestStream = [IO.File]::OpenRead($Path)
    $taskDigestAlgorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($taskDigestAlgorithm.ComputeHash($taskDigestStream)).Replace('-', '') }
    finally { $taskDigestAlgorithm.Dispose(); $taskDigestStream.Dispose() }
}
$taskEngineManifest = Get-Content -LiteralPath "$PSScriptRoot/pdfcontrol/localchat_engine_manifest.json" -Raw | ConvertFrom-Json
$taskEngineFiles = @($taskEngineManifest.sha256.PSObject.Properties.Name) + @('localchat_engine_manifest.json','octop_mcp.py','bureaus.json','bureaus_prive.json','mbs_artikelnamen.json','mbs_catalogus.json','kennisbank.json','lower_right_ignored_labels.json')
foreach ($taskEngineName in $taskEngineFiles) {
    $taskEngineSource = Join-Path "$PSScriptRoot/pdfcontrol" $taskEngineName
    if (Test-Path -LiteralPath $taskEngineSource) {
        $taskEngineTarget = Join-Path "$DistributionPath/pdfcontrol" $taskEngineName
        New-Item -ItemType Directory -Path (Split-Path $taskEngineTarget -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $taskEngineSource -Destination $taskEngineTarget -Force
        if ((Get-EngineDigest $taskEngineSource) -ne (Get-EngineDigest $taskEngineTarget)) { throw "Installed PDFCONTROL differs: $taskEngineName" }
    }
}
# User-imported runtime snapshots/registry/archives are authoritative. Seed missing catalogues only.
& $taskPdfPython -I "$PSScriptRoot/integration/seed_catalogues.py" --source "$PSScriptRoot/pdfcontrol/data_lokaal" --target "$DistributionPath/pdfcontrol/data_lokaal"
if ($LASTEXITCODE) { throw 'Factory catalogue initialization failed' }
if (!(Test-Path -LiteralPath "$DistributionPath/runtime/bin/javaw.exe")) {
    if (Test-Path -LiteralPath "$DistributionPath/runtime") { throw 'Incomplete runtime; restore it before building' }
    & "$taskJdk/bin/jlink.exe" --add-modules java.base,java.desktop,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --compress=2 --output "$DistributionPath/runtime"
    if ($LASTEXITCODE) { throw 'Portable runtime creation failed' }
}
Write-Output "LocalChat $((Get-Content -LiteralPath "$PSScriptRoot/VERSION" -Raw).Trim()) built and tested: $DistributionPath"
exit 0

param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Output,
    [ValidateSet(0,16)][int]$OutputFormat=16
)
$ErrorActionPreference='Stop'
$taskSource=[IO.Path]::GetFullPath($Source)
$taskOutput=[IO.Path]::GetFullPath($Output)
if(!(Test-Path -LiteralPath $taskSource -PathType Leaf) -or $taskSource -eq $taskOutput -or (Test-Path -LiteralPath $taskOutput)){throw 'Kies een bestaande bron en een nieuw tijdelijk uitvoerbestand.'}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class LocalChatWordJob {
 [StructLayout(LayoutKind.Sequential)] struct Basic { public long Time1,Time2; public uint Flags; public UIntPtr Min,Max; public uint Active; public UIntPtr Affinity; public uint Priority,Schedule; }
 [StructLayout(LayoutKind.Sequential)] struct Counters { public ulong A,B,C,D,E,F; }
 [StructLayout(LayoutKind.Sequential)] struct Extended { public Basic Basic; public Counters IO; public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob; }
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,IntPtr info,uint length);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr value);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window,out uint process);
 public static void Limit(IntPtr job,bool kill) { var value=new Extended();value.Basic.Flags=kill?0x2000u:0u;int size=Marshal.SizeOf(value);IntPtr data=Marshal.AllocHGlobal(size);try{Marshal.StructureToPtr(value,data,false);if(!SetInformationJobObject(job,9,data,(uint)size))throw new System.ComponentModel.Win32Exception();}finally{Marshal.FreeHGlobal(data);} }
 public static IntPtr Own(int pid) {IntPtr job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new System.ComponentModel.Win32Exception();try{Limit(job,true);using(var p=System.Diagnostics.Process.GetProcessById(pid)){if(!AssignProcessToJobObject(job,p.Handle))throw new System.ComponentModel.Win32Exception();}return job;}catch{CloseHandle(job);throw;} }
}
'@
$taskBefore=@(Get-Process WINWORD -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$taskWord=$null;$taskDoc=$null;$taskProbeDoc=$null;$taskJob=[IntPtr]::Zero;$taskOwned=$false
$taskSecurity=$null;$taskAlerts=$null;$taskLinks=$null;$taskVisible=$null;$taskChanged=$false
$taskProbeFile=Join-Path ([IO.Path]::GetTempPath()) ('localchat-word-probe-'+[Guid]::NewGuid().ToString('N')+'.docx')
try {
    $taskWord=New-Object -ComObject Word.Application
    if($taskWord.Documents.Count -ne 0){throw 'Geen leeg afzonderlijk Word-object beschikbaar; bestaande documenten blijven onaangeraakt.'}
    $taskSecurity=$taskWord.AutomationSecurity;$taskAlerts=$taskWord.DisplayAlerts;$taskLinks=$taskWord.Options.UpdateLinksAtOpen;$taskVisible=$taskWord.Visible;$taskChanged=$true
    $taskWord.AutomationSecurity=3
    if($taskWord.AutomationSecurity -ne 3){throw 'Macroblokkering kon niet worden bevestigd.'}
    $taskWord.DisplayAlerts=0;$taskWord.Options.UpdateLinksAtOpen=$false;$taskWord.Visible=$false
    # A macro-free synthetic probe gives a Word Window.Hwnd before any source is opened.
    [IO.File]::WriteAllBytes($taskProbeFile,[Convert]::FromBase64String('UEsDBBQAAAAIAKF4cl33S4B1wgAAAHYBAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbH2QuQ7CMAyGX6XKiqgRAwOiLMAKDLyAlbptRC7FLsfbk3INCBjt//gsLw7XSFxcnPVcqU4kzgFYd+SQyxDJZ6UJyaHkMbUQUR+xJZhOJjPQwQt5GcvQoZaLNTXYWyk2l7xmE3ylEllWxephHFiVwhit0ShZh5OvPyjjJ6HMybuHOxN5lA0KvhIG5TfgmdudKCVTU7HHJFt02QXnkGqog+5dTpb/a77cGZrGaHrnh7aYgiZm41tny7fi0PjX/XB/9/IGUEsDBBQAAAAIAKF4cl1hey9DiQAAAPIAAAALAAAAX3JlbHMvLnJlbHONzzsOAiEQBuCrEA6ws1pYGKCy2dZ4AQLDIy6PDBj19lJYrMbCcuaffH9GnHHVPZbcQqyNPdKam+Sh93oEaCZg0m0qFfNIXKGk+xjJQ9Xmqj3Cfp4PQFuDK7E12WIlp8XuOLs8K/5jF+eiwVMxt4S5/6j4uhiyJo9d8nshC/a9ngbLQQn4eFG9AFBLAwQUAAAACACheHJdrVl7D4EAAACsAAAAEQAAAHdvcmQvZG9jdW1lbnQueG1sRc5BDgIhDAXQq5A5gJ24cEGQjVsvwQAOJNCSgqK3F8aFm/fT/OSnqktH9pk9NvHOCavs1yW0ViRAtcFnU09UPI7uQZxNGyfv0IldYbK+1oh7TnBe1wtkE3HRqsuN3GdmmfCk6TtZk27BNGEJX55rJBRjY/MKZj/lw3L424D/f/oLUEsBAhQAFAAAAAgAoXhyXfdLgHXCAAAAdgEAABMAAAAAAAAAAAAAAIABAAAAAFtDb250ZW50X1R5cGVzXS54bWxQSwECFAAUAAAACACheHJdYXsvQ4kAAADyAAAACwAAAAAAAAAAAAAAgAHzAAAAX3JlbHMvLnJlbHNQSwECFAAUAAAACACheHJdrVl7D4EAAACsAAAAEQAAAAAAAAAAAAAAgAGlAQAAd29yZC9kb2N1bWVudC54bWxQSwUGAAAAAAMAAwC5AAAAVQIAAAAA'))
    $taskProbeDoc=$taskWord.Documents.Open($taskProbeFile,$false,$true,$false)
    if($taskProbeDoc.Windows.Count -lt 1){throw 'Word leverde geen identificeerbaar conversievenster.'}
    [uint32]$taskWordPid=0
    [void][LocalChatWordJob]::GetWindowThreadProcessId([IntPtr]$taskProbeDoc.Windows.Item(1).Hwnd,[ref]$taskWordPid)
    if(!$taskWordPid -or $taskBefore -contains $taskWordPid -or $taskWord.Documents.Count -ne 1){throw 'Geen afzonderlijk Word-proces beschikbaar; bestaande documenten blijven onaangeraakt.'}
    $taskOwned=$true
    $taskJob=[LocalChatWordJob]::Own([int]$taskWordPid)
    $taskProbeDoc.Close(0);[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($taskProbeDoc);$taskProbeDoc=$null
    $taskDoc=$taskWord.Documents.Open($taskSource,$false,$true,$false)
    if(!$taskDoc.ReadOnly){throw 'Bron is niet alleen-lezen geopend.'}
    $taskDoc.SaveAs2($taskOutput,$OutputFormat)
    if(!(Test-Path -LiteralPath $taskOutput -PathType Leaf)){throw 'Word leverde geen geconverteerd bestand.'}
    @{converted=$true;macros_disabled=$true;source_opened_readonly=$true;isolated_process=$true} | ConvertTo-Json -Compress
} finally {
    if($null -ne $taskDoc){try{$taskDoc.Close(0)}catch{};[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($taskDoc)}
    if($null -ne $taskProbeDoc){try{$taskProbeDoc.Close(0)}catch{};[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($taskProbeDoc)}
    if($null -ne $taskWord){
        if($taskChanged){try{$taskWord.AutomationSecurity=$taskSecurity;$taskWord.DisplayAlerts=$taskAlerts;$taskWord.Options.UpdateLinksAtOpen=$taskLinks;if(!$taskOwned){$taskWord.Visible=$taskVisible}}catch{}}
        if($taskOwned){
            try {if($taskWord.Documents.Count -eq 0){$taskWord.Quit(0)}elseif($taskJob -ne [IntPtr]::Zero){[LocalChatWordJob]::Limit($taskJob,$false)}}catch{}
        }
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($taskWord)
    }
    if($taskJob -ne [IntPtr]::Zero){[void][LocalChatWordJob]::CloseHandle($taskJob)}
    Remove-Item -LiteralPath $taskProbeFile -Force -ErrorAction SilentlyContinue
}

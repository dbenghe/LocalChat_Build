package localchat;
/** Detect provider protocol debris outside quoted examples; never execute markup as tools. */
final class ToolProtocol {
 static boolean malformed(String text){StringBuilder out=new StringBuilder();boolean fence=false;for(String line:text.split("\\R")){String t=line.strip();if(t.startsWith("```")||t.startsWith("~~~")){fence=!fence;continue;}if(!fence&&!t.startsWith(">"))out.append(line.replaceAll("`[^`]*`","")).append('\n');}String visible=out.toString();return visible.contains("<minimax:tool_call>")||visible.matches("(?s).*<invoke\\s+name=[\\\"'].*</invoke>.*");}
 static final String REPAIR="\nThe preceding assistant reply incorrectly serialized a tool request as text/XML. No text/XML request was executed. Use the provider's native structured tool_calls, using the supplied tool schemas, or give a normal final answer if no tool is needed. Do not print <invoke>, <parameter>, or <minimax:tool_call> markup. Resume from confirmed tool results already in the conversation; do not repeat completed writes, copies or other operations. If a Python .py file was created successfully, call that existing file with the correct cwd instead of recreating it.\n";
}

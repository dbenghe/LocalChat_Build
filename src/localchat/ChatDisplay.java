package localchat;
/** Display-only, one UTF-16 code unit in/out: originals and selection offsets stay intact. */
final class ChatDisplay {
    static String text(String original){
        if(!I18n.language().equals("ro"))return original;
        char[] chars=I18n.romanianAscii(original).toCharArray();
        for(int i=0;i<chars.length;i++)if(chars[i]>=0x2500 && chars[i]<=0x257f){
            chars[i]=switch(chars[i]){
                case '\u2500','\u2501','\u2504','\u2505','\u2508','\u2509','\u254c','\u254d','\u2550','\u2574','\u2576','\u2578','\u257a','\u257c','\u257e'->'-';
                case '\u2502','\u2503','\u2506','\u2507','\u250a','\u250b','\u254e','\u254f','\u2551','\u2575','\u2577','\u2579','\u257b','\u257d','\u257f'->'|';
                case '\u2571'->'/';case '\u2572'->'\\';case '\u2573'->'x';default->'+';
            };
        }
        return new String(chars);
    }
}

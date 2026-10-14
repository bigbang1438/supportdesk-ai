package com.supportdesk;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import static com.supportdesk.ApiModels.PolicyView;
import static com.supportdesk.AssistantModels.Citation;

/** BM25 over immutable, authorized policy snapshots. Citations always retain original source lines. */
@Component
public class KnowledgeRetriever {
    private static final Pattern TOKENS = Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);
    private record Scored(Citation citation, double score) {}

    public List<Citation> search(List<PolicyView> policies, String query) {
        Set<String> terms = tokens(query).keySet();
        if (terms.isEmpty()) return List.of();
        List<Citation> chunks = policies.stream().flatMap(policy -> chunks(policy).stream()).toList();
        if (chunks.isEmpty()) return List.of();
        List<Map<String,Integer>> frequencies = chunks.stream().map(chunk -> tokens(chunk.text())).toList();
        double average = frequencies.stream().mapToInt(map -> map.values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1);
        Map<String,Long> documentFrequency = new HashMap<>();
        for (String term : terms) documentFrequency.put(term, frequencies.stream().filter(map -> map.containsKey(term)).count());
        List<Scored> scored = new ArrayList<>();
        for (int i=0;i<chunks.size();i++) {
            var tf = frequencies.get(i); int length = tf.values().stream().mapToInt(Integer::intValue).sum(); double score = 0;
            for (String term : terms) {
                int count = tf.getOrDefault(term,0); if (count==0) continue;
                double idf = Math.log(1 + (chunks.size()-documentFrequency.get(term)+0.5)/(documentFrequency.get(term)+0.5));
                score += idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * length / Math.max(average,1)));
            }
            if (score>0) scored.add(new Scored(chunks.get(i),score));
        }
        return scored.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()
            .thenComparingLong(item -> item.citation().documentId()).thenComparing(item -> item.citation().chunkId()))
            .limit(3).map(Scored::citation).toList();
    }

    private static Map<String,Integer> tokens(String text) {
        var result = new HashMap<String,Integer>(); var matcher = TOKENS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) != Character.UnicodeScript.HAN) result.merge(token,1,Integer::sum);
            else {
                int[] points = token.codePoints().toArray();
                for (int i=0;i<points.length;i++) {
                    result.merge(new String(points,i,1),1,Integer::sum);
                    if(i+1<points.length) result.merge(new String(points,i,2),1,Integer::sum);
                }
            }
        }
        return result;
    }

    private static List<Citation> chunks(PolicyView policy) {
        String[] lines = policy.content().replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        List<Citation> result = new ArrayList<>(); StringBuilder current = new StringBuilder(); int start=0,end=0;
        for (int index=0;index<lines.length;index++) {
            String line = lines[index]; int lineNumber=index+1;
            if (line.length()>600) {
                if(start>0) add(result,policy,start,end,current.toString()); current.setLength(0); start=0;
                for(int offset=0;offset<line.length();) {
                    int limit=Math.min(offset+600,line.length());
                    if(limit<line.length()&&Character.isHighSurrogate(line.charAt(limit-1))&&Character.isLowSurrogate(line.charAt(limit))) limit--;
                    add(result,policy,lineNumber,lineNumber,line.substring(offset,limit)); offset=limit;
                }
            } else {
                if(start>0 && current.length()+1+line.length()>600) { add(result,policy,start,end,current.toString()); current.setLength(0); start=0; }
                if(start==0) start=lineNumber; else current.append('\n'); current.append(line); end=lineNumber;
            }
        }
        if(start>0) add(result,policy,start,end,current.toString());
        return result;
    }
    private static void add(List<Citation> chunks,PolicyView policy,int first,int last,String text) {
        chunks.add(new Citation(policy.id(),policy.filename(),first,last,text,policy.id()+":"+chunks.size()));
    }
}

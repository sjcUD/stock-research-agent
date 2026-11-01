package io.github.sjcud.stockagent.runtime.harness;

/**
 * 上下文保护与确定性裁剪策略。
 *
 * <p>prepare保留最初三行演示入口；实际研究使用compact结构化接口，不把历史字符串前缀当作本轮事实。</p>
 */
public class ContextPolicy {
    /** 保留前三行研究身份与引用，再截断过长历史。 */
    public String prepare(String context, int maxCharacters) {
        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters 必须大于0");
        }
        String[] lines = context.split("\\n");
        StringBuilder protectedPart = new StringBuilder();
        StringBuilder remainder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i < 3) {
                protectedPart.append(lines[i]).append('\n');
            } else {
                remainder.append(lines[i]).append('\n');
            }
        }
        String prefix = protectedPart.toString();
        int remaining = Math.max(0, maxCharacters - prefix.length());
        if (remainder.length() <= remaining) {
            return prefix + remainder;
        }
        return prefix + remainder.substring(0, remaining);
    }
    /** 结构化裁剪：问题/事实不变，历史有配额，完整证据按分数纳入，不截断数字或引用。 */
    public Prepared compact(String stage,String question,String facts,java.util.List<String> history,
            java.util.List<Evidence> evidence,int limit,int historyLimit) {
        if(question==null||question.trim().isEmpty()||facts==null||history==null||evidence==null||limit<=0||historyLimit<0)
            throw new IllegalArgumentException("上下文参数无效");
        String protectedPart=facts.isEmpty()?"":"【当前关键事实】\n"+facts+"\n";
        String hh="【历史仅作背景，必须重新核验】\n",eh="【本轮检索证据】\n";
        java.util.List<String> cleaned=new java.util.ArrayList<String>();int stripped=0;
        for(String turn:history) {
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\[片段=[^\\]]+\\]").matcher(turn);
            StringBuffer text=new StringBuffer();while(matcher.find()){stripped++;matcher.appendReplacement(text,"");}matcher.appendTail(text);cleaned.add(text.toString()+"\n");
        }
        int before=TokenEstimator.add(TokenEstimator.estimate(question+protectedPart),TokenEstimator.estimate(history.isEmpty()?"":hh+join(history)));
        for(Evidence hit:evidence)before=TokenEstimator.add(before,TokenEstimator.estimate(hit.render()));
        if(!evidence.isEmpty())before=TokenEstimator.add(before,TokenEstimator.estimate(eh));
        int protectedEstimate=TokenEstimator.estimate(question+protectedPart);
        if(protectedEstimate>limit)throw new ContextLimitException(new Report(stage,before,protectedEstimate,protectedEstimate,limit,history.size(),0,evidence.size(),0,java.util.Collections.emptyList(),stripped,"REJECTED"));
        int historyBudget=Math.min(historyLimit,(limit-protectedEstimate)/3),usedHistory=0;
        java.util.List<String> keptHistory=new java.util.ArrayList<String>();
        for(int i=cleaned.size()-1;i>=0;i--) {
            String text=cleaned.get(i);int cost=TokenEstimator.estimate(text)+(keptHistory.isEmpty()?TokenEstimator.estimate(hh):0);
            if(usedHistory+cost<=historyBudget){keptHistory.add(0,text);usedHistory+=cost;}
        }
        String supplement=protectedPart+(keptHistory.isEmpty()?"":hh+join(keptHistory));
        int used=TokenEstimator.estimate(question+supplement);
        java.util.List<Evidence> sorted=new java.util.ArrayList<Evidence>(evidence);
        sorted.sort(java.util.Comparator.comparingDouble((Evidence e)->e.score).reversed());
        java.util.List<String> selected=new java.util.ArrayList<String>(),dropped=new java.util.ArrayList<String>();StringBuilder hits=new StringBuilder();
        for(Evidence hit:sorted) {
            int cost=TokenEstimator.estimate(hit.render())+(selected.isEmpty()?TokenEstimator.estimate(eh):0);
            if((long)used+cost<=limit){if(selected.isEmpty())hits.append(eh);hits.append(hit.render());used+=cost;selected.add(hit.id);}else dropped.add(hit.id);
        }
        supplement+=hits.toString();
        return new Prepared(question,supplement,selected,new Report(stage,before,used,protectedEstimate,limit,history.size(),keptHistory.size(),evidence.size(),selected.size(),dropped,stripped,"APPLIED"));
    }
    /** 拼接已分组文本，避免半条消息或半个引用。 */
    private String join(java.util.List<String> values){StringBuilder b=new StringBuilder();for(String s:values)b.append(s).append(s.endsWith("\n")?"":"\n");return b.toString();}
    /** 证据是原子片段，分数只用于选择，不生成金融事实。 */
    public static final class Evidence {
        /** 稳定引用ID、完整文本与检索分数。 */
        public final String id,text;public final double score;
        /** 验证引用与有限数，避免NaN扰乱排序。 */
        public Evidence(String id,String text,double score){if(id==null||id.isEmpty()||text==null||!Double.isFinite(score))throw new IllegalArgumentException("证据无效");this.id=id;this.text=text;this.score=score;}
        /** 引用和正文一起纳入或移除。 */
        private String render(){return "[片段="+id+"] "+text+"\n";}
    }
    /** 当前问题单独传给角色，supplement不重复当前问题。 */
    public static final class Prepared {
        /** 不可变原问题、补充上下文和保留来源。 */
        private final String question,supplement;
        private final java.util.List<String> selected;
        private final Report report;
        private Prepared(String q,String s,java.util.List<String> ids,Report r){question=q;supplement=s;selected=java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(ids));report=r;}
        /** 原样返回当前问题。 */
        public String getQuestion(){return question;}
        /** 返回完整被选中的事实/历史/证据。 */
        public String getSupplement(){return supplement;}
        /** 返回实际发送给模型的来源ID集合。 */
        public java.util.List<String> getSelectedEvidenceIds(){return selected;}
        /** 返回不含正文的裁剪元数据。 */
        public Report getReport(){return report;}
    }
    /** 数据上下文估算报告；实际完整模型提示词还含角色规则，预算另行计算。 */
    public static final class Report {
        /** 阶段和处理结果。 */
        public final String stage,status;
        /** UTF8字节启发式估算，不是实际tokenizer或账单usage。 */
        public final int beforeEstimate,afterEstimate,protectedEstimate,limit,historyBefore,historyAfter,evidenceBefore,evidenceAfter,historyCitationsRemoved;
        /** 明确移除的完整证据ID，不能继续当作本轮来源。 */
        public final java.util.List<String> droppedEvidenceIds;
        /** 构造不可变诊断信息。 */
        public Report(String stage,int before,int after,int protect,int limit,int hb,int ha,int eb,int ea,java.util.List<String> dropped,int stripped,String status){
            this.stage=stage;this.status=status;beforeEstimate=before;afterEstimate=after;protectedEstimate=protect;this.limit=limit;historyBefore=hb;historyAfter=ha;evidenceBefore=eb;evidenceAfter=ea;historyCitationsRemoved=stripped;droppedEvidenceIds=java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(dropped));
        }
    }
}

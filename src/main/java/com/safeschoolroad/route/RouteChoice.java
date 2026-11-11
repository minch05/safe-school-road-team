package com.safeschoolroad.route;

import com.safeschoolroad.route.RouteCandidateService.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/** 설명 가능한 비교 시작점. 안전 점수나 가장 안전하다는 판정이 아니다. */
public final class RouteChoice {
    private RouteChoice() {}
    public record Suggestion(int index,List<String> reasons,String rule,String limitation,String summary,String basis) {
        public Suggestion(int index,List<String> reasons,String rule,String limitation,String summary) {this(index,reasons,rule,limitation,summary,"REFERENCE");}
    }
    public static Suggestion suggest(List<Candidate> list) {
        if(list.isEmpty())return new Suggestion(-1,List.of(),"","","");
        int fastest=list.stream().mapToInt(c->c.analysis().route().timeSeconds()).min().orElse(0);
        var eligible=new ArrayList<Integer>();
        for(int i=0;i<list.size();i++)if(list.get(i).analysis().route().timeSeconds()<=fastest+120)eligible.add(i);
        eligible=minimum(eligible,i->list.get(i).analysis().accidentAreas().size());
        eligible=minimum(eligible,i->list.get(i).analysis().crossingsUnknown());
        boolean crimeComparable=list.stream().allMatch(c->c.crime().available()&&c.crime().coveragePercent()>=90);
        boolean crimeUsed=false;
        if(crimeComparable && eligible.size()>1){
            var before=List.copyOf(eligible);
            eligible.removeIf(i->before.stream().anyMatch(j->i.intValue()!=j.intValue() && crimeClearlyShorter(list.get(j),list.get(i))));
            crimeUsed=eligible.size()<before.size();
        }
        eligible=minimum(eligible,i->list.get(i).analysis().crossingCount());
        int chosen=eligible.stream().min(Comparator.<Integer>comparingInt(i->list.get(i).analysis().route().timeSeconds())
            .thenComparingInt(i->list.get(i).analysis().route().distanceMeters())).orElse(0);
        var a=list.get(chosen).analysis();
        boolean measuredDifference = crimeUsed || list.stream().anyMatch(c -> c.analysis().route().timeSeconds()<=fastest+120 && (c.analysis().accidentAreas().size()!=a.accidentAreas().size() || c.analysis().crossingsUnknown()!=a.crossingsUnknown() || c.analysis().crossingCount()!=a.crossingCount()));
        String basis = list.size()==1 ? "SINGLE" : measuredDifference ? "DATA_DIFFERENCE" : "TIME_DISTANCE";
        var reasons=new ArrayList<String>();
        if(list.size()==1)reasons.add("탐색 방식이 같은 길로 합쳐져 비교할 다른 후보가 없어요.");
        else {
            reasons.add("가장 빠른 후보보다 예상 시간이 2분 이내로 더 걸리는 길들을 먼저 비교했어요.");
            reasons.add("그 안에서 등록된 사고 영역과의 겹침, 신호 미확인 건넘이 적은 후보를 먼저 살폈어요.");
            if(crimeUsed)reasons.add("미확인 부분을 고려해도 과거 범죄 자료의 주의 참고 구간이 분명히 짧은 후보를 우선했어요.");
            else reasons.add("범죄 자료의 확인 범위나 차이가 충분하지 않아 이 항목으로 후보의 우열을 정하지 않았어요.");
            reasons.add("이후 건넘 수가 적은 길, 예상 시간과 거리가 짧은 길 순으로 골랐어요.");
        }
        for (int i = 0; i < list.size(); i++) {
            if (i == chosen) continue;
            var other = list.get(i).analysis();
            StringBuilder difference = new StringBuilder((i + 1) + "번 길과 비교: ");
            int seconds = a.route().timeSeconds() - other.route().timeSeconds();
            difference.append(seconds == 0 ? "예상 시간 같음" : "예상 " + Math.abs(seconds) + "초 " + (seconds > 0 ? "더 걸림" : "덜 걸림"));
            difference.append(" · 사고 영역 ").append(a.accidentAreas().size()).append("곳 / 상대 ").append(other.accidentAreas().size()).append("곳");
            difference.append(" · 신호 미확인 ").append(a.crossingsUnknown()).append("곳 / 상대 ").append(other.crossingsUnknown()).append("곳");
            if (other.route().timeSeconds() > fastest + 120) difference.append(" · 상대 후보는 빠른 길 +2분을 넘어 자동 제안 대상에서 제외했어요");
            reasons.add(difference.toString());
        }
        reasons.add("이 길의 확인값: 건넘 "+a.crossingCount()+"곳 · 신호 미확인 "+a.crossingsUnknown()+"곳 · 등록된 사고 영역 겹침 "+a.accidentAreas().size()+"곳.");
        String rule=list.size()==1 ? "다른 후보와의 순위 비교는 하지 않았어요. 지도에서 출입구와 건너는 곳, 주의할 위치를 확인한 뒤 선택해 주세요."
                : "빠른 길 +2분 이내 → 등록된 사고 영역 겹침 → 신호 미확인 → 분명한 범죄 자료 차이 → 건넘 수 → 시간·거리. 다른 후보도 직접 선택할 수 있어요.";
        return new Suggestion(chosen,List.copyOf(reasons),rule,
            "가장 안전한 길이라는 뜻은 아니에요. 자료 누락·현재 교통 상황은 반영하지 못해요. CCTV나 보호구역 수를 안전 점수로 더하지 않아요. 범죄는 모든 후보의 자료 확인 비율이 90% 이상이고 차이가 오차를 넘어설 때만 반영해요.", summary(list, chosen, crimeUsed), basis);
    }
    private static String summary(List<Candidate> list, int chosen, boolean crimeUsed) {
        if (list.size() == 1) return "비교할 다른 후보가 없어요. 이 길의 위치와 주의할 곳을 확인해 주세요.";
        if (crimeUsed) return "자료의 미확인 부분을 고려해도 과거 범죄 참고 구간이 분명히 짧은 후보를 우선했어요.";
        var a = list.get(chosen).analysis();
        // 후보 순서 대신 계산 우선순위로 중요한 차이를 설명한다.
        for (int criterion = 0; criterion < 3; criterion++) {
        for (int i = 0; i < list.size(); i++) {
            if (i == chosen) continue;
            var b = list.get(i).analysis();
            int accidentDifference = b.accidentAreas().size() - a.accidentAreas().size();
            int signalDifference = b.crossingsUnknown() - a.crossingsUnknown();
            int crossingDifference = b.crossingCount() - a.crossingCount();
            if (b.route().timeSeconds() > list.stream().mapToInt(c -> c.analysis().route().timeSeconds()).min().orElse(0) + 120) continue;
            String reason = criterion == 0 && accidentDifference > 0 ? "등록된 사고 영역 겹침이 " + accidentDifference + "곳 적어요."
                    : criterion == 1 && accidentDifference == 0 && signalDifference > 0 ? "신호를 확인하지 못한 건넘이 " + signalDifference + "곳 적어요."
                    : criterion == 2 && accidentDifference == 0 && signalDifference == 0 && crossingDifference > 0 ? "건너는 곳이 " + crossingDifference + "곳 적어요."
                    : "";
            if (!reason.isEmpty()) return (i + 1) + "번 길보다 예상 " + timeDifference(a.route().timeSeconds() - b.route().timeSeconds()) + " " + reason;
        }
        }
        boolean same = list.stream().allMatch(c -> c.analysis().accidentAreas().size() == a.accidentAreas().size()
                && c.analysis().crossingsUnknown() == a.crossingsUnknown() && c.analysis().crossingCount() == a.crossingCount());
        return same ? "사고 영역·신호 미확인·건넘 수는 같아요. 짧은 우회 범위에서 예상 시간과 거리로 골랐어요."
                : "빠른 길 +2분 이내에서 사고 영역·신호 미확인·건넘 수를 차례로 비교해 골랐어요.";
    }
    private static String timeDifference(int seconds) {
        if (seconds == 0) return "시간은 같고";
        return Math.abs(seconds) + "초 " + (seconds > 0 ? "더 걸리지만" : "덜 걸리고");
    }

    private static ArrayList<Integer> minimum(List<Integer> options,ToIntFunction<Integer> metric){
        int min=options.stream().mapToInt(metric).min().orElse(0);
        return new ArrayList<>(options.stream().filter(i->metric.applyAsInt(i)==min).toList());
    }
    static boolean crimeClearlyShorter(Candidate a,Candidate b){
        return a.crime().available()&&b.crime().available()&&a.crime().coveragePercent()>=90&&b.crime().coveragePercent()>=90
            && a.crime().upperGradeMeters()+a.crime().unknownMeters()+10<b.crime().upperGradeMeters();
    }
}

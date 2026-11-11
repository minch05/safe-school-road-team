# 안내문 시험 결과 비교: python3 metrics.py 결과.json ...
import json, sys, re, statistics
from collections import Counter

def named(p):
    parts = [x for x in re.split(r'\s*·\s*', p['name'] or '') if len(x) > 1 and x != '가로변']
    return parts

for f in sys.argv[1:]:
    d = json.load(open(f, encoding='utf-8'))
    ai = [r for r in d if r.get('source') == 'AI']
    texts = [p['text'] for r in ai for p in r['points']]
    first = sum(1 for r in ai if r['attempts'] == 1)
    secs = [r['seconds'] for r in ai]
    # 이름이 있는 지점에서 이름을 쓴 비율 (길 이름·학교 이름 → 아이가 알아보기 쉬움)
    has_name = [(p, named(p)) for r in ai for p in r['points'] if named(p)]
    used_name = sum(1 for p, ns in has_name if any(n in p['text'] for n in ns))
    # 경로 안에서 같은 문장 반복, 전체에서 3번 넘게 나온 똑같은 문장 비율(틀에 박힌 문장)
    dup_in_route = sum(len(r['points']) - len({p['text'] for p in r['points']}) for r in ai)
    cnt = Counter(texts)
    stock = sum(c for t, c in cnt.items() if c > 3)
    # 신호 미확인 횡단보도에서 "신호등이 없을 수도" 같은 말을 하는지
    print(f"== {f}")
    print(f"  AI {len(ai)}/{len(d)}  첫 시도 통과 {first}/{len(ai)}  평균 {statistics.mean(secs):.1f}s  문장 {len(texts)}개 평균 {statistics.mean(map(len, texts)):.0f}자")
    print(f"  이름 있는 지점에서 이름 씀 {used_name}/{len(has_name)} ({used_name / max(1, len(has_name)):.0%})")
    print(f"  경로 안 같은 문장 {dup_in_route}  틀에 박힌 문장(4번 이상 똑같음) {stock}/{len(texts)} ({stock / max(1, len(texts)):.0%})")
    T = re.compile(r'꺾|돌아|틀어|방향을 바꿔')
    pts = [p for r in ai for p in r['points']]
    turn = [p for p in pts if p['reason'] == '꺾음']
    print(f"  꺾는 곳에서 꺾는 말 빠짐 {sum(1 for p in turn if not T.search(p['text']))}/{len(turn)}  안 꺾는 곳에서 꺾으라 함 {sum(1 for p in pts if p['reason'] != '꺾음' and T.search(p['text']))}")
    for t, c in cnt.most_common(3):
        print(f"    {c}× {t}")

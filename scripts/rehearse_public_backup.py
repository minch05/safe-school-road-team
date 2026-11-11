"""공개시설 백업을 새 SQLite 검증 DB에 복원하고 전 행 대조. 운영 DB 접근 없음."""
import argparse,hashlib,json,sqlite3,tempfile
from pathlib import Path
FIELDS={'facilities':{'kind':'TEXT','name':'TEXT','lat':'REAL','lng':'REAL','signal_status':'TEXT','district':'TEXT','source':'TEXT','source_id':'TEXT','base_date':'TEXT'},'risks':{'kind':'TEXT','name':'TEXT','area_json':'TEXT','district':'TEXT','source':'TEXT','source_id':'TEXT','base_year':'INTEGER'}}
def rehearse(file):
 raw=file.read_bytes();data=json.loads(raw);result={}
 if set(data)!=set(FIELDS):raise ValueError('공개시설/사고 백업만 허용')
 with tempfile.TemporaryDirectory() as folder:
  con=sqlite3.connect(str(Path(folder)/'restored.sqlite'));con.row_factory=sqlite3.Row
  for table,fields in FIELDS.items():
   rows=data[table]
   if not isinstance(rows,list) or any(set(r)!=set(fields) for r in rows):raise ValueError('백업 필드 불일치')
   con.execute('CREATE TABLE '+table+'('+','.join(k+' '+v for k,v in fields.items())+')')
   con.executemany('INSERT INTO '+table+' VALUES('+','.join('?' for k in fields)+')',[tuple(r[k] for k in fields) for r in rows]);con.commit()
   restored=[dict(r) for r in con.execute('SELECT * FROM '+table)]
   if rows!=restored:raise ValueError('복원 행 대조 실패')
   result[table]={'rows':len(rows),'allFieldsMatch':True}
  check=con.execute('PRAGMA integrity_check').fetchone()[0];con.close()
 return {'backupSha256':hashlib.sha256(raw).hexdigest(),'passed':check=='ok','tables':result,'limits':['격리 SQLite에서 백업의 전 행/필드 복원 검증','MySQL 운영 복원·원래 자동증가 ID·인덱스·권한 복구 시험은 아님','계정/GPS/사용자 경로는 백업·검증 대상 아님']}
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('backup',type=Path);p.add_argument('--report',type=Path,required=True);a=p.parse_args();r=rehearse(a.backup);a.report.write_text(json.dumps(r,ensure_ascii=False,indent=2));print(json.dumps(r,ensure_ascii=False))

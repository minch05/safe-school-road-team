import json,tempfile,unittest
from pathlib import Path
from rehearse_public_backup import rehearse,FIELDS
class BackupTests(unittest.TestCase):
 def run_backup(self,data):
  with tempfile.TemporaryDirectory() as folder:
   p=Path(folder)/'public.json';p.write_text(json.dumps(data));return rehearse(p)
 def test_all_public_fields_roundtrip(self):
  row={k:None for k in FIELDS['facilities']};row.update(kind='CCTV',name='검수 시설',lat=37.6,lng=127.,source='PUBLIC',source_id='001')
  r=self.run_backup({'facilities':[row],'risks':[]});self.assertTrue(r['passed']);self.assertEqual(r['tables']['facilities']['rows'],1)
 def test_user_tables_are_rejected(self):
  with self.assertRaises(ValueError):self.run_backup({'facilities':[],'risks':[],'members':[]})
 def test_missing_columns_are_rejected(self):
  with self.assertRaises(ValueError):self.run_backup({'facilities':[{'kind':'CCTV'}],'risks':[]})

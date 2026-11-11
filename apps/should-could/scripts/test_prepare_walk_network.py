import csv
import tempfile
import unittest
from pathlib import Path
from prepare_walk_network import prepare

class WalkPreparationTests(unittest.TestCase):
    def data(self, missing=False, special=False):
        nodes=[('1',126.98,37.57),('2',126.98,37.5697),('3',126.9804,37.57),('4',126.9796,37.57)]
        rows=[]
        for id,x,y in nodes:
            r=['']*23; r[0]='NODE'; r[1]=f'POINT({x} {y})';r[2]=id;r[11]='종로구';rows.append(r)
        for i,(id,x,y) in enumerate(nodes[1:]):
            r=['']*23;r[0]='LINK';r[4]=f'LINESTRING(126.98 37.57,{x} {y})';r[5]=str(i+10);r[7]='1';r[8]=('999' if missing and i==0 else id)
            r[14:22]=['0']*8
            if special and i==0:r[19]='1'
            rows.append(r)
        return rows
    def run_prepare(self, rows):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'raw.csv'
            with path.open('w',encoding='cp949',newline='') as f:
                w=csv.writer(f);h=['']*23;h[1]='노드 WKT';w.writerow(h);w.writerows(rows)
            return prepare(path)
    def test_uses_original_coordinates_and_counts(self):
        result=self.run_prepare(self.data());self.assertEqual(len(result['junctions']),1)
        self.assertEqual(result['junctions'][0]['lat'],37.57);self.assertEqual(result['counts']['NODE'],4)
    def test_incomplete_or_crossing_connections_are_not_promoted(self):
        for rows in (self.data(missing=True),self.data(special=True)):
            with self.assertRaises(ValueError):self.run_prepare(rows)
    def test_rejects_conflicting_identity_and_bad_coordinate(self):
        rows=self.data();bad=rows[0].copy();bad[1]='POINT(126.99 37.57)';rows.append(bad)
        with self.assertRaises(ValueError):self.run_prepare(rows)
        rows=self.data();rows[0][1]='POINT(nan 37.57)'
        with self.assertRaises(ValueError):self.run_prepare(rows)

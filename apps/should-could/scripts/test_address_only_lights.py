import unittest
from audit_address_only_lights import classify,inventory,sample

class AddressOnlyLightsTests(unittest.TestCase):
    def test_shared_address_is_not_multiple_verified_positions(self):
        a=[{'소재지도로명주소':'서울 A'}]*3+[{'소재지지번주소':'서울 B'},{}]
        self.assertEqual(inventory(a),{'rows':5,'withAddress':4,'uniqueAddresses':2,'rowsSharingAddress':3,'largestAddressGroup':3})
        self.assertEqual(sample(a),['서울 A','서울 B'])
    def test_ambiguous_wrong_district_and_nan_are_not_accepted(self):
        a={'roadAddress':'서울특별시 마포구 A','x':'126.95','y':'37.55'}
        self.assertEqual(classify({'addresses':[a,a]},'마포구'),'복수 후보')
        self.assertEqual(classify({'addresses':[a]},'용산구'),'다른 구 후보')
        self.assertEqual(classify({'addresses':[{**a,'y':'nan'}]},'마포구'),'서울 범위 밖')
    def test_success_remains_unverified_facility(self):
        a={'jibunAddress':'서울특별시 용산구 A','x':'126.95','y':'37.55'}
        self.assertIn('시설 위치 미검증',classify({'addresses':[a]},'용산구'))

if __name__=='__main__':unittest.main()

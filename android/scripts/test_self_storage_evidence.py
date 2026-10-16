#!/usr/bin/env python3
"""Negative acceptance cases for root accounting and owned image identity."""
import copy
import unittest
from verify_self_storage import verify

FILE = '/data/app/~~owned/dev.tripoder.selfhookprobe-owned/oat/arm64/base.art'
MAPS = ('1000-2000 rw-p 00000000 00:00 0 [anon:dalvik-' + FILE + ']\n'
        '8000-9000 r--p 00004000 fe:45 123 ' + FILE + '\n')


def storage(moving=False):
    return {'published': 19, 'rootedOutsideDeclaredTables': 19,
            'observedOwnerMoves': 19 if moving else 0, 'observedContainerRootMoves': 57,
            'rootSnapshots': [dict(slot=i, rooted=True, target=hex(0x1000+i*32), backup=hex(0x9000+i*32),
                                   ownerBefore='1100', ownerAfter='1200' if moving else '1100',
                                   targetOwner='1200' if moving else '1100', backupOwner='1200' if moving else '1100',
                                   containersBefore=['3000','4000','5000'], containersAfter=['3100','4100','5100'])
                              for i in range(19)]}


class EvidenceTest(unittest.TestCase):
    def reject(self, data=None, maps=MAPS, mode='cold'):
        with self.assertRaises(RuntimeError):
            verify(data if data is not None else storage(), maps, mode)

    def test_cold_image(self):
        result = verify(storage(), MAPS, 'cold')
        self.assertEqual(len(result['imageOwners']), 19)
        self.assertFalse(result['ownerMovementProven'])

    def test_truncated_label(self):
        maps = MAPS.replace('[anon:dalvik-' + FILE, '[anon:dalvik-/data/app/~~ow...probe-owned/oat/arm64/base.art')
        self.assertEqual(verify(storage(), maps, 'cold')['containerSlotMoves'], 57)

    def test_strict_modes_require_movement(self):
        for mode in ['jit', 'aot']:
            self.reject(mode=mode)
            self.assertTrue(verify(storage(True), MAPS, mode)['ownerMovementProven'])

    def test_cold_moving_owners(self):
        self.assertTrue(verify(storage(True), '', 'cold')['ownerMovementProven'])

    def test_missing_image_file(self):
        self.reject(maps=MAPS.splitlines()[0])

    def test_foreign_image(self):
        self.reject(maps=MAPS.replace('dev.tripoder.selfhookprobe', 'other.application'))

    def test_mismatched_image(self):
        self.reject(maps=MAPS.replace('123 '+FILE, '123 '+FILE.replace('~~owned', '~~different')))

    def test_unmapped_owner(self):
        self.reject(maps=MAPS.replace('1000-2000', '2000-3000'))

    def test_duplicate_mapping(self):
        self.reject(maps=MAPS+MAPS.splitlines()[0])

    def test_false_owner_count(self):
        data=storage(); data['observedOwnerMoves']=19; self.reject(data)

    def test_false_container_count(self):
        data=storage(); data['observedContainerRootMoves']=56; self.reject(data)

    def test_no_container_movement(self):
        data=storage(); data['observedContainerRootMoves']=0
        for row in data['rootSnapshots']: row['containersAfter']=copy.copy(row['containersBefore'])
        self.reject(data)

    def test_lost_root(self):
        data=storage(); data['rootSnapshots'][0]['rooted']=False; self.reject(data)

    def test_wrong_backup_owner(self):
        data=storage(); data['rootSnapshots'][0]['backupOwner']='1200'; self.reject(data)

    def test_missing_slot(self):
        data=storage(); data['rootSnapshots'].pop(); self.reject(data)

    def test_backup_is_declared_target(self):
        data=storage(); data['rootSnapshots'][0]['backup']=data['rootSnapshots'][0]['target']; self.reject(data)

    def test_unchanged_nonimage_owner_even_with_other_moves(self):
        data=storage(True); data['observedOwnerMoves']=18
        data['rootSnapshots'][0].update(ownerBefore='3000',ownerAfter='3000',targetOwner='3000',backupOwner='3000')
        self.reject(data)


if __name__ == '__main__':
    unittest.main()

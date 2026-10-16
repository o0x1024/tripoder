#ifndef TP_ART_ENTRY_WRITER_H
#define TP_ART_ENTRY_WRITER_H
#include "../art_access.h"
#include "near_entry_leaf.h"
#include "entry_branch_page.h"
bool tp_art_entry_writer_prepare(const struct tp_art_access *);
bool tp_art_entry_writer_activate(void);
bool tp_art_entry_writer_installed(void);
/* Diagnostic immutable leases; reading requires prepare's acquire boundary. */
const struct tp_near_entry_leaf *tp_art_entry_writer_leaf(void);
const struct tp_entry_branch_page *tp_art_entry_writer_page(void);
#endif

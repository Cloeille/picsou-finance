-- "Impôts & taxes" joins the seeded default categories (CategoryService.DEFAULTS).
--
-- Income tax, taxe foncière, taxe d'habitation and URSSAF calls are recurring, sizeable and
-- none of the existing defaults describes them: they landed in "Divers" or stayed in the
-- review inbox. New members get the row from the seed; every member seeded before this
-- migration needs it written here, because `ensureSeeded` is a one-shot that only fires when
-- a member has no categories at all.
--
-- Appended at the end of each member's list (MAX(sort_order) + 1) rather than slotted into
-- the seed position: renumbering would reshuffle categories the member may have reordered
-- themselves. Members with zero categories are skipped -- their first read still seeds the
-- full, up-to-date default set.
INSERT INTO category (member_id, slug, name, kind, color, icon, is_default, sort_order)
SELECT c.member_id,
       'impots',
       'Impôts & taxes',
       'EXPENSE'::category_kind,
       '#a16207',
       'landmark',
       TRUE,
       MAX(c.sort_order) + 1
FROM category c
GROUP BY c.member_id
HAVING COUNT(*) FILTER (WHERE c.slug = 'impots') = 0;

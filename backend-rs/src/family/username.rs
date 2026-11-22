//! UsernameDeriver: a readable, login-safe username from a member's display name.

use crate::db::DbHandle;
use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

use crate::auth::users;

const USERNAME_COLUMN_LENGTH: usize = 50;
const MAX_BASE_LENGTH: usize = 45;

/// The base before collision handling: accents stripped (NFD, marks removed), lower-cased,
/// every run of other characters collapsed to one dot, dots trimmed, "user" when nothing is
/// left, capped at 45 characters to leave room for a suffix.
pub fn base(display_name: &str) -> String {
    let stripped: String = display_name
        .nfd()
        .filter(|c| !is_combining_mark(*c))
        .collect::<String>()
        .to_lowercase();
    let mut out = String::with_capacity(stripped.len());
    let mut pending_dot = false;
    for c in stripped.chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            if pending_dot && !out.is_empty() {
                out.push('.');
            }
            pending_dot = false;
            out.push(c);
        } else {
            pending_dot = true;
        }
    }
    if out.is_empty() {
        return "user".to_owned();
    }
    out.truncate(MAX_BASE_LENGTH);
    out
}

/// The base, or the base with ".2", ".3", ... when taken; every candidate fits the column.
pub async fn derive(conn: &mut DbHandle<'_>, display_name: &str) -> sqlx::Result<String> {
    let base = base(display_name);
    let mut candidate = base.clone();
    let mut n: u64 = 2;
    while users::exists_by_username(&mut *conn, &candidate).await? {
        let suffix = format!(".{n}");
        n += 1;
        let keep = base.len().min(USERNAME_COLUMN_LENGTH - suffix.len());
        candidate = format!("{}{suffix}", &base[..keep]);
    }
    Ok(candidate)
}

#[cfg(test)]
mod tests {
    use super::base;

    #[test]
    fn strips_accents_and_collapses_separators() {
        assert_eq!(base("Élodie"), "elodie");
        assert_eq!(base("Jean --- Dupont"), "jean.dupont");
        assert_eq!(base("  .Jean Dupont. "), "jean.dupont");
        assert_eq!(base("Isabelle"), "isabelle");
        assert_eq!(base("Nguyễn Thị"), "nguyen.thi");
    }

    #[test]
    fn falls_back_to_user_and_caps_the_base() {
        assert_eq!(base("!!!"), "user");
        assert_eq!(base(""), "user");
        assert_eq!(base(&"a".repeat(50)), "a".repeat(45));
    }
}

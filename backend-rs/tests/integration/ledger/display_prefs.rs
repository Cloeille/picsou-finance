//! The viewer's display preferences.

use picsou::ledger::display_prefs;
use picsou::ledger::model::AccountType;
use picsou::testing::TestApp;

use super::support::{account_of, member};

#[tokio::test]
async fn upsert_writes_only_the_given_fields_and_clear_forgets_positions() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let account = account_of(&app, alice, AccountType::Checking, "1").await;
    let db = &app.state.db;

    display_prefs::upsert(db, account.id, alice, None, Some(true))
        .await
        .unwrap();
    let pref = display_prefs::find_by_member_and_account(db, alice, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!((pref.list_position, pref.list_collapsed), (None, true));

    display_prefs::upsert(db, account.id, alice, Some(3), None)
        .await
        .unwrap();
    let pref = display_prefs::find_by_member_and_account(db, alice, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!((pref.list_position, pref.list_collapsed), (Some(3), true));

    display_prefs::clear_positions(db, alice).await.unwrap();
    let prefs = display_prefs::find_by_member(db, alice).await.unwrap();
    assert_eq!(prefs.len(), 1);
    assert_eq!(
        (prefs[0].list_position, prefs[0].list_collapsed),
        (None, true)
    );
}

from scripts.cards_loo import exact_class, question


def test_question_hides_the_card_itself_and_exact_class_matches_fixed_classes():
    me = {"id": "K1", "증상": "9호선 0대", "증상로그": "", "원인": "반전", "고친곳": "SubwayDirectionNormalizer, #339"}
    other = {**me, "id": "K2", "증상": "성수지선"}
    assert "반전" not in question(me, None)
    with_cards = question(me, [other])
    assert "[과거 장애 K2]" in with_cards and "[과거 장애 K1]" not in with_cards
    assert exact_class(me, "고칠 곳: SubwayDirectionNormalizer") and not exact_class(me, "고칠 곳: 어댑터")

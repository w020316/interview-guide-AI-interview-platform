# -*- coding: utf-8 -*-
"""embedding-service 鉴权逻辑单测（不依赖 fastembed，也不联网）。

运行方式（在仓库根目录）：

    python -m unittest discover -s interview-guide/embedding-service -p "test_*.py"

为什么值得单独测：这两个函数承载的是**安全语义**——
「未配置令牌必须拒绝（fail-closed）」这条如果被人改回「为空则放行」，
服务会在误删环境变量时对公网完全开放，而这在运行时几乎看不出来。
"""
import unittest

from server import is_authorized, parse_tokens


class ParseTokensTest(unittest.TestCase):
    def test_empty_variants(self):
        self.assertEqual(parse_tokens(""), [])
        self.assertEqual(parse_tokens(None), [])
        self.assertEqual(parse_tokens("   "), [])

    def test_single(self):
        self.assertEqual(parse_tokens("abc"), ["abc"])

    def test_multi_with_spaces_and_trailing_comma(self):
        self.assertEqual(parse_tokens("new123, old456 ,"), ["new123", "old456"])


class IsAuthorizedTest(unittest.TestCase):
    def test_fail_closed_when_no_tokens(self):
        """核心安全语义：未配置令牌必须拒绝（旧实现是放行）。"""
        self.assertFalse(is_authorized("Bearer anything", []))
        self.assertFalse(is_authorized("", []))

    def test_allow_no_auth_is_explicit_opt_in(self):
        """本地调试开关：只有显式打开才放行。"""
        self.assertTrue(is_authorized("", [], allow_no_auth=True))

    def test_single_token_strict_format(self):
        self.assertTrue(is_authorized("Bearer aaa", ["aaa"]))
        self.assertFalse(is_authorized("Bearer bbb", ["aaa"]))
        self.assertFalse(is_authorized("aaa", ["aaa"]))          # 缺少 Bearer 前缀
        self.assertFalse(is_authorized("Bearer aaa ", ["aaa"]))  # 尾随空格不匹配

    def test_rotation_window_accepts_both_tokens(self):
        """零中断轮换：过渡期内新、旧令牌都应被接受，其它值拒绝。"""
        tokens = ["new123", "old456"]
        self.assertTrue(is_authorized("Bearer new123", tokens))
        self.assertTrue(is_authorized("Bearer old456", tokens))
        self.assertFalse(is_authorized("Bearer other", tokens))

    def test_after_rotation_old_token_rejected(self):
        """轮换完成后，旧令牌必须失效。"""
        self.assertFalse(is_authorized("Bearer old456", ["new123"]))


if __name__ == "__main__":
    unittest.main()

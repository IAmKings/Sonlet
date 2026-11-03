"""验证仓库的中文提交标题检查；仅使用标准库。"""
import subprocess
import tempfile
import unittest
from pathlib import Path

HOOK = Path(__file__).resolve().parents[1] / ".githooks" / "commit-msg"


class CommitMessageTest(unittest.TestCase):
    def test_commit_titles(self):
        cases = [
            ("初始化仓库并建立设计规范\n", True),
            ("修复 AudioRecord 取消逻辑\n\n保留已有文本。\n", True),
            ("Initialize repository\n", False),
            ("Initialize repository\n\n中文正文不能代替中文标题。\n", False),
            ("🎉\n", False),
            ("", False),
        ]
        with tempfile.TemporaryDirectory() as directory:
            message = Path(directory) / "提交信息"
            for content, accepted in cases:
                with self.subTest(content=content):
                    message.write_text(content, encoding="utf-8")
                    result = subprocess.run([str(HOOK), str(message)], capture_output=True)
                    self.assertEqual(result.returncode == 0, accepted)
                    if not accepted:
                        self.assertIn("提交标题必须使用中文", result.stderr.decode("utf-8"))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
# 生成合成评测语料(仅 macOS:依赖 say 与 afconvert)。
# 用法: python3 scripts/gen_corpus_tts.py [输出目录]
import sys
import subprocess, wave, os, csv, pathlib

ZH = """今天天气不错我们去公园散步吧|帮我把会议时间改到明天下午三点|这份报告需要在周五之前完成|我记得钥匙放在门口的鞋柜上面|冰箱里还有两个鸡蛋和一盒牛奶|请把空调温度调低两度|他说明天早上会准时到达车站|这道菜有点咸下次可以少放点盐|我们下午去图书馆借几本书|火车还有二十分钟就要开了|妈妈的生日是下个月的第一个星期天|我把充电器落在办公室了|这条路正在施工建议绕行|孩子今天在学校表现得很好|天气预报说周末会下大雨|记得出门之前把垃圾带下去|会议室的投影仪好像坏了|我订了两张明天晚上的电影票|楼下新开了一家面馆味道很不错|请帮我把这些文件复印三份|他每天早上六点起床跑步|这个方案还需要再讨论一次|我的手机快没电了需要充电|周末我们一起去爬山怎么样|快递显示已经放在门卫处了|医生建议多喝水多休息|这次的考试成绩比上次好很多|会议延迟到下午四点开始|我把照片都整理到网盘里了|明天记得带伞可能会下雨""".split("|")

EN = """The meeting has been moved to three in the afternoon|Please send me the report before Friday|I left my keys on the kitchen table|We should book the tickets as soon as possible|The weather is going to be cold this weekend|Could you turn down the volume a little|She said she would arrive around noon|The train leaves in twenty minutes|Remember to bring your passport tomorrow|This restaurant is famous for its noodles|I need to charge my phone before we leave|The children are playing in the garden|He has been working here for five years|Please close the window it is quite windy|Our flight was delayed by two hours|I will call you back in ten minutes|The package arrived earlier than expected|We need to finish this task by Thursday|She bought a new laptop last week|The doctor advised him to rest for a few days|Turn left at the next traffic light|I have already sent the email to the team|The movie starts at seven thirty|Could you help me carry these boxes|They are planning a trip to the mountains|The coffee here is better than the one downtown|He forgot to bring his umbrella again|We should leave before the traffic gets heavy|The project will be finished by the end of the month|Please let me know if you need anything else""".split("|")

MIX = """我用GitHub提交了这个feature|把文件存成PDF格式再发给我|这个bug在iOS上也能复现|我在App Store下载了一个新工具|记得更新一下钉钉里的日程|这份PPT的第三页需要改颜色|会议链接我发到微信群里了|用Excel把这个表格重新统计一遍|他的MacBook昨天突然黑屏了|我们下周一开个Zoom会议吧|这个API返回的数据格式有问题|请把截图发到飞书上|我在淘宝买了一个机械键盘|这个版本的Android有兼容性问题|帮我把代码push到main分支|今天的WiFi信号特别差|我用ChatGPT翻译了这段英文|发票需要开成公司抬头|这个链接在Chrome里打不开|我在Notion里整理了会议记录|请把Word文档转成PDF|这个功能要在iPhone上测试一下|我在小红书上看到一家店|记得把U盘还给我|这个report需要用中文再写一遍|我在B站看了一个教程视频|服务器在凌晨两点重启|请把账单发到我的邮箱|这个app的加载速度有点慢|我在地铁上用蓝牙耳机听歌""".split("|")

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/androidTest/assets/corpus"
os.makedirs(OUT, exist_ok=True)
VOICE = {"zh": "Tingting", "en": "Samantha", "mixed": "Tingting"}
RATE = {"zh": 175, "en": 180, "mixed": 175}
rows = []
for lang, texts in (("zh", ZH), ("en", EN), ("mixed", MIX)):
    for i, text in enumerate(texts, 1):
        name = f"{lang}_{i:03d}.wav"
        aiff = os.path.join(OUT, name.replace(".wav", ".aiff"))
        wav = os.path.join(OUT, name)
        subprocess.run(["say", "-v", VOICE[lang], "-r", str(RATE[lang]), "-o", aiff, text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav], check=True)
        os.remove(aiff)
        with wave.open(wav) as w:
            assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
            dur = w.getnframes() / 16000
        rows.append((name, lang, text, round(dur, 2)))

with open(os.path.join(OUT, "manifest.csv"), "w", encoding="utf-8") as f:
    f.write("# Sonlet 评测语料（合成）\n")
    f.write("# 由 scripts/gen_corpus_tts.sh 用 macOS say 生成，16kHz 单声道 PCM16；\n")
    f.write("# 合成语音缺少口音、口吃、背景噪声，只用于回归门槛，不代表真人分布。\n")
    f.write("# file,lang,ground_truth\n")
    for name, lang, text, _ in rows:
        f.write(f"{name},{lang},{text}\n")

total = sum(os.path.getsize(os.path.join(OUT, r[0])) for r in rows)
print(f"clips={len(rows)} total={total/1024/1024:.1f}MB dur_avg={sum(r[3] for r in rows)/len(rows):.2f}s")

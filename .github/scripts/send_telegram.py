import json, os, sys

caption = sys.argv[1]
sha = os.environ['SHA']
author = os.environ['COMMIT_AUTHOR']
repo = os.environ['REPO']
apks = sys.argv[2:]

caption_text = (
    '🎉 *检测到新的 GitHub 推送！*\n'
    f'*Commit SHA:* `{sha}`\n'
    f'```\n{caption}\n```\n'
    f'*提交者* `{author}`\n'
    f'[查看详情](https://github.com/{repo}/commit/{sha})'
)

media = []
for i, apk in enumerate(apks):
    name = f'apk{i}'
    item = {'type': 'document', 'media': f'attach://{name}'}
    if i == 0:
        item['caption'] = caption_text
        item['parse_mode'] = 'MarkdownV2'
    media.append(item)

print(json.dumps(media))

from pathlib import Path
p=Path('frontend/src/FlowEditor.vue').read_bytes()
print(p[:100])
text=p.decode('utf-8')
for line in text.splitlines()[120:145]: print(repr(line))

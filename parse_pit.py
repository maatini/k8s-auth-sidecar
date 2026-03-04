import re
with open('/Volumes/SSD2TB/work/antigravity/rr-sidecar/target/pit-reports/space.maatini.sidecar.service/ProxyService.java.html', 'r') as f:
    html = f.read()

matches = re.finditer(r"<span class='pop'>(\d+)\.<span><b>Location : </b>.*?</span></span> (.*? replaced .*? \&rarr; SURVIVED)</span>", html, re.DOTALL)
for m in matches:
    print(f"Line {m.group(1)}: {m.group(2)}")

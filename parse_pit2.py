import re
with open('/Volumes/SSD2TB/work/antigravity/rr-sidecar/target/pit-reports/space.maatini.sidecar.service/ProxyService.java.html', 'r') as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "SURVIVED" in line and "replaced" in line:
        print(line.strip())

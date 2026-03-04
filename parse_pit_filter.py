with open('/Volumes/SSD2TB/work/antigravity/rr-sidecar/target/pit-reports/space.maatini.sidecar.filter/AuthProxyFilter.java.html', 'r') as f:
    lines = f.readlines()

last_line_num = -1
for line in lines:
    if "<a name='" in line:
        last_line_num = line.split("<a name='")[1].split("'")[0]
    if "SURVIVED" in line and "replaced" in line:
        print(f"Line {last_line_num} SURVIVED: {line.strip()}")
    if "NO_COVERAGE" in line and "replaced" in line:
         print(f"Line {last_line_num} NO_COVERAGE: {line.strip()}")

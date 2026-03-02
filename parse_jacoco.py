import xml.etree.ElementTree as ET
import csv
import sys

tree = ET.parse('target/jacoco-report/jacoco.xml')
root = tree.getroot()

classes_to_check = [
    'space/maatini/sidecar/service/WasmPolicyEngine',
    'space/maatini/sidecar/filter/RateLimitFilter',
    'space/maatini/sidecar/service/ProxyService',
    'space/maatini/sidecar/filter/AuditLogFilter',
    'space/maatini/sidecar/model/AuthContext',
    'space/maatini/sidecar/service/AuthenticationService',
    'space/maatini/sidecar/service/RolesService'
]

for pkg in root.findall('package'):
    for cls in pkg.findall('class'):
        name = cls.get('name')
        if name in classes_to_check:
            print(f"--- {name} ---")
            for method in cls.findall('method'):
                method_name = method.get('name')
                for counter in method.findall('counter'):
                    if counter.get('type') == 'BRANCH':
                        missed = int(counter.get('missed'))
                        if missed > 0:
                            print(f"  Method: {method_name}, Missed Branches: {missed}")
            
            # Find lines
            src_file = cls.get('sourcefilename')
            for sf in pkg.findall('sourcefile'):
                if sf.get('name') == src_file:
                    for line in sf.findall('line'):
                        cb = int(line.get('cb', 0))
                        mb = int(line.get('mb', 0))
                        if mb > 0:
                            print(f"  Line {line.get('nr')}: Missed Branches: {mb}/{mb+cb}")


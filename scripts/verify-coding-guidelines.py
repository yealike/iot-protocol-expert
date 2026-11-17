"""Alibaba guideline subset for this Java 21 / PostgreSQL project (stdlib only)."""
from pathlib import Path
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
BASELINE = json.loads((ROOT / "scripts/coding-guidelines-baseline.json").read_text(encoding="utf-8"))
# Identifiers cannot be bound with #{...}. These uses require service-side allowlists.
INTERPOLATION = {
    "pep-control/src/main/resources/mapper/ops/DeviceInstanceMapper.xml": {"orderBy"},
    "pep-control/src/main/resources/mapper/ops/ProductMapper.xml": {"orderBy"},
    "pep-control/src/main/resources/mapper/ops/AlarmRecordMapper.xml": {"orderBy"},
    "pep-control/src/main/resources/mapper/tdengine/TdengineMapper.xml": {"database", "table"},
}
# The metering ledger needs single-statement CTE/row-lock SQL with explicit
# transaction control; reviewed exception, not a general JDBC escape hatch.
JDBC_REVIEWED = {
    "pep-model-gateway/src/main/java/top/yealike/pep/modelgateway/catalog/CatalogRepository.java",
    "pep-model-gateway/src/main/java/top/yealike/pep/modelgateway/quota/QuotaRepository.java",
}
errors = []
checked = 0
for module in sorted(ROOT.glob("pep-*")):
    for path in sorted((module / "src/main").rglob("*")):
        if not path.is_file() or path.suffix not in {".java", ".xml", ".sql"}:
            continue
        checked += 1
        name = path.relative_to(ROOT).as_posix()
        if "/src/main/test/" in name:
            continue
        raw = path.read_text(encoding="utf-8")
        text = re.sub(r"/\*.*?\*/|<!--.*?-->", "", raw, flags=re.S)
        if path.suffix == ".java":
            text = re.sub(r"//[^\n]*", "", text)
            rules = {
                "explicit-executor": r"Executors\.new\w+\s*\(",
                "explicit-locale": r"\.to(?:Lower|Upper)Case\(\)",
                "mybatis-only-persistence":
                    r"org\.springframework\.jdbc\.(?:core\.)?(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)",
                "no-jpa-persistence": r"org\.hibernate\.|(?:jakarta|javax)\.persistence\.",
                "no-driver-manager": r"java\.sql\.DriverManager\.getConnection\s*\(",
                "no-console-stacktrace": r"\.printStackTrace\s*\(",
                "thread-safe-date-format": r"new\s+SimpleDateFormat\s*\(",
            }
            for rule, pattern in rules.items():
                if rule == "mybatis-only-persistence" and name in JDBC_REVIEWED:
                    continue
                if re.search(pattern, text):
                    errors.append(f"{name}: {rule}")
        if path.suffix == ".xml" and "mapper/" in name:
            if re.search(r"\bselect\s+(?:\w+\.)?\*", text, re.I):
                errors.append(f"{name}: explicit-select-columns")
            for expression in re.findall(r"\$\{([^}]+)\}", text):
                if expression not in INTERPOLATION.get(name, set()):
                    errors.append(f"{name}: unreviewed-sql-interpolation {expression}")
        if path.suffix == ".sql":
            text = re.sub(r"--[^\n]*", "", text)
            if re.search(r"\breferences\s+[a-z_]|\bforeign\s+key\s*\(|\bon\s+delete\s+(?:cascade|set\s+null)", text, re.I):
                if BASELINE.get(name) != hashlib.sha256(raw.encode("utf-8")).hexdigest():
                    errors.append(f"{name}: no-new-foreign-keys (applied migrations must remain unchanged)")
for error in errors:
    print(error)
print(f"Coding guidelines: {checked} files checked, {len(errors)} violations")
sys.exit(bool(errors))

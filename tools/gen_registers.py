#!/usr/bin/env python3
"""
Generate src/main/resources/luxtronik/registers.json from the python-luxtronik
register definitions.

python-luxtronik (https://github.com/Bouni/python-luxtronik) is the community
source of truth for what the ~1000 Luxtronik CFI registers mean. Instead of
hand-porting that table to Java we introspect the installed package and dump
everything the Java side needs (register definitions + datatype semantics)
into one JSON resource that is baked into the jar.

Usage:
    pip install luxtronik            # or: pip install -e <clone of python-luxtronik>
    python tools/gen_registers.py    # writes src/main/resources/luxtronik/registers.json

Re-run whenever upstream adds/renames registers, then rebuild the jar.
"""

import datetime
import importlib.metadata
import inspect
import json
import os
import subprocess
import sys

import luxtronik
from luxtronik import datatypes as dt
from luxtronik.definitions.calculations import CALCULATIONS_DEFINITIONS_LIST
from luxtronik.definitions.parameters import PARAMETERS_DEFINITIONS_LIST
from luxtronik.definitions.visibilities import VISIBILITIES_DEFINITIONS_LIST

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "luxtronik", "registers.json")

# Datatypes whose conversion is implemented explicitly in Java (DataType.Kind).
SPECIAL_KINDS = {
    "Bool": "bool",
    "Timestamp": "timestamp",
    "IPv4Address": "ipv4",
    "Version": "version",
    "FullVersion": "fullversion",
    "Character": "character",
    "MajorMinorVersion": "majorminor",
    "Hours2": "hours2",
    "TimeOfDay": "timeofday",
    "TimeOfDay2": "timeofday2",
}


def describe_type(cls):
    """Turn a python-luxtronik datatype class into a JSON-able description."""
    name = cls.__name__
    d = {
        "class": getattr(cls, "datatype_class", None),
        "unit": getattr(cls, "datatype_unit", None),
        "concatenate": bool(getattr(cls, "concatenate_multiple_data_chunks", True)),
    }
    if name in SPECIAL_KINDS:
        d["kind"] = SPECIAL_KINDS[name]
    elif issubclass(cls, dt.ScalingBase):
        d["kind"] = "scaling"
        d["factor"] = cls.scaling_factor
        d["width"] = cls.data_width
        d["signed"] = cls.data_type == "signed"
        d["precision"] = cls.precision
    elif issubclass(cls, dt.SelectionBase):
        d["kind"] = "selection"
        d["codes"] = {str(k): v for k, v in cls.codes.items()}
    elif issubclass(cls, dt.BitMaskBase):
        d["kind"] = "bitmask"
        d["bits"] = {str(k): v for k, v in cls.bit_values.items()}
        d["zero"] = cls.value_zero
        d["delim"] = cls.value_delim
        d["postfix"] = cls.values_postfix
    else:
        d["kind"] = "raw"
    return d


def all_datatypes():
    result = {}
    for name, cls in inspect.getmembers(dt, inspect.isclass):
        if issubclass(cls, dt.Base) and cls.__module__ == dt.__name__:
            result[name] = describe_type(cls)
    return result


def convert_defs(defs):
    out = []
    for d in defs:
        out.append({
            "index": int(d["index"]),
            "count": int(d.get("count", 1)),
            "names": list(d["names"]) if isinstance(d["names"], list) else [str(d["names"])],
            "type": d["type"].__name__,
            "writeable": bool(d.get("writeable", False)),
            "datatype": d.get("datatype", "") or "",
            "unit": d.get("unit", "") or "",
            "description": d.get("description", "") or "",
            "since": str(d.get("since", "") or ""),
            "until": str(d.get("until", "") or ""),
            "successor": d.get("successor"),
        })
    return out


def source_info():
    info = {"generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
    try:
        info["package_version"] = importlib.metadata.version("luxtronik")
    except importlib.metadata.PackageNotFoundError:
        info["package_version"] = "unknown"
    pkg_dir = os.path.dirname(luxtronik.__file__)
    try:
        commit = subprocess.check_output(["git", "-C", pkg_dir, "rev-parse", "HEAD"], stderr=subprocess.DEVNULL)
        info["git_commit"] = commit.decode().strip()
    except Exception:
        pass
    return info


def main():
    data = {
        "source": source_info(),
        "datatypes": all_datatypes(),
        "calculations": convert_defs(CALCULATIONS_DEFINITIONS_LIST),
        "parameters": convert_defs(PARAMETERS_DEFINITIONS_LIST),
        "visibilities": convert_defs(VISIBILITIES_DEFINITIONS_LIST),
    }
    used_types = {d["type"] for k in ("calculations", "parameters", "visibilities") for d in data[k]}
    missing = used_types - set(data["datatypes"])
    if missing:
        sys.exit(f"datatypes referenced but not described: {missing}")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"wrote {os.path.normpath(OUT)}: "
          f"{len(data['calculations'])} calculations, {len(data['parameters'])} parameters, "
          f"{len(data['visibilities'])} visibilities, {len(data['datatypes'])} datatypes "
          f"(python-luxtronik {data['source'].get('package_version')} {data['source'].get('git_commit', '')[:10]})")


if __name__ == "__main__":
    main()

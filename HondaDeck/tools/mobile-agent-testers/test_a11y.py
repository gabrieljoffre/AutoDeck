"""Unit verification for mobile-agent-testers a11y.py and personas.json on HondaDeck."""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import a11y  # noqa: E402


def main():
    personas = json.loads((HERE / "personas.json").read_text(encoding="utf-8"))
    assert len(personas) == 4, f"Expected 4 personas, found {len(personas)}"
    required_ids = {
        "sm_t500_driver",
        "anna_large_font_130",
        "malik_fast_tapper",
        "clara_no_permissions",
    }
    assert {p["id"] for p in personas} == required_ids, "Persona IDs mismatch"

    # SM-T500 (2000x1200, 240dpi -> 48dp = 72px): passing interactive hierarchy
    good_xml = """<hierarchy>
      <node bounds="[0,0][2000,1200]" clickable="false" enabled="true">
        <node text="NAVEGACAO" bounds="[30,220][520,300]" clickable="true" enabled="true" />
        <node content-desc="Status e Configuracoes" bounds="[1880,10][1960,82]" clickable="true" enabled="true" />
        <node content-desc="Inicio" bounds="[600,1110][672,1182]" clickable="true" enabled="true" />
        <node text="Dispensar" bounds="[1400,20][1490,92]" clickable="true" enabled="true" />
        <node content-desc="Barra de progresso da musica" bounds="[1480,620][1940,656]" clickable="false" enabled="true" />
      </node>
    </hierarchy>"""
    res_good = a11y.android(ET.fromstring(good_xml), 240, 2000, 1200)
    assert res_good is None, f"Expected passing screen, got: {res_good}"

    # Regression fixture: unnamed & small (<48dp) clickable control must be caught
    bad_xml = """<hierarchy>
      <node bounds="[0,0][2000,1200]" clickable="false" enabled="true">
        <node text="" content-desc="" bounds="[100,100][130,130]" clickable="true" enabled="true" />
      </node>
    </hierarchy>"""
    res_bad = a11y.android(ET.fromstring(bad_xml), 240, 2000, 1200)
    assert res_bad is not None and "with no name" in res_bad and "under 48 dp" in res_bad, (
        f"Expected unnamed+small failure, got: {res_bad}"
    )

    print("MOBILE_AGENT_A11Y_OK (4 personas + SM-T500 bounds verified)")


if __name__ == "__main__":
    main()

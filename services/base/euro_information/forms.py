"""Read HTML forms the way a browser submits them.

The Euro-Information pages drive every action through one big form whose hidden
fields carry the page state; a postback that drops one of them is answered with
"Navigation interdite". So a form is replayed whole: every hidden and text field
as served, plus the one submit button pressed.
"""

import html as html_module
import re
from dataclasses import dataclass, field

_FORM_RE = re.compile(r"<form\b(?P<attrs>[^>]*)>(?P<body>.*?)</form>", re.DOTALL | re.IGNORECASE)
_INPUT_RE = re.compile(r"<input\b(?P<attrs>[^>]*)/?>", re.IGNORECASE)
_ATTR_RE = re.compile(r'(?P<name>[\w:-]+)\s*=\s*(?:"(?P<dq>[^"]*)"|\'(?P<sq>[^\']*)\')')
_QUOTED_RE = re.compile(r'"[^"]*"|\'[^\']*\'')
_TAG_RE = re.compile(r"<[^>]+>")
# `checked` as an attribute name (bare or with a value), never inside a value.
_CHECKED_RE = re.compile(r"(?<![\w:-])checked(?![\w:-])", re.IGNORECASE)

# Field types a browser sends without user action. Submit buttons are sent only
# when pressed, checkboxes and radios only when checked.
_SENT_TYPES = {"hidden", "text", "password", "email", "tel", "number", ""}


@dataclass
class Form:
    action: str
    method: str
    name: str = ""
    id: str = ""
    fields: dict[str, str] = field(default_factory=dict)
    # Submit buttons, name -> value: sent only when pressed.
    buttons: dict[str, str] = field(default_factory=dict)


def html_text(fragment: str) -> str:
    """The text of an HTML fragment: tags dropped, entities decoded, whitespace (non-breaking included) collapsed."""
    return " ".join(html_module.unescape(_TAG_RE.sub(" ", fragment)).split())


def _attrs(raw: str) -> dict[str, str]:
    return {
        match.group("name").lower(): html_module.unescape(
            match.group("dq") if match.group("dq") is not None else match.group("sq") or ""
        )
        for match in _ATTR_RE.finditer(raw)
    }


def parse_forms(page: str) -> list[Form]:
    forms: list[Form] = []
    for match in _FORM_RE.finditer(page):
        attrs = _attrs(match.group("attrs"))
        form = Form(
            action=attrs.get("action", ""),
            method=attrs.get("method", "get").lower(),
            name=attrs.get("name", ""),
            id=attrs.get("id", ""),
        )
        for field_match in _INPUT_RE.finditer(match.group("body")):
            field_attrs = _attrs(field_match.group("attrs"))
            name = field_attrs.get("name")
            if not name:
                continue
            kind = field_attrs.get("type", "").lower()
            if kind == "submit":
                form.buttons[name] = field_attrs.get("value", "")
            elif kind in ("checkbox", "radio"):
                if _CHECKED_RE.search(_QUOTED_RE.sub('""', field_match.group("attrs"))):
                    form.fields[name] = field_attrs.get("value", "on")
            elif kind in _SENT_TYPES:
                form.fields[name] = field_attrs.get("value", "")
        forms.append(form)
    return forms


def find_form(page: str, *, name: str | None = None, button: str | None = None) -> Form | None:
    """The form named `name`, or the one holding the submit button `button`."""
    for form in parse_forms(page):
        if name is not None and form.name == name:
            return form
        if button is not None and button in form.buttons:
            return form
    return None


def field_names(page: str) -> list[str]:
    """Names of every form field on a page, for diagnostics. Never the values."""
    return sorted({name for form in parse_forms(page) for name in (*form.fields, *form.buttons)})

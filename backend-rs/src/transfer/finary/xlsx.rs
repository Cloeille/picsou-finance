//! The part of Apache POI's XSSF reader the Finary import relies on: sheets by name (case
//! insensitive, as Workbook.getSheet), rows by index and each cell's POI type.

use std::collections::{BTreeMap, HashMap};
use std::io::{Cursor, Read};

use quick_xml::events::{BytesStart, Event};
use quick_xml::{Reader, XmlVersion};

use super::java_double::{self, JavaDouble};

/// Inflated size allowed for the parts of one package together. A real export is a few
/// megabytes; the tree built from a part weighs many times its XML.
const MAX_INFLATED_BYTES: u64 = 32 * 1024 * 1024;

/// Nesting allowed in a part. SpreadsheetML stays under a dozen levels; the tree is dropped and
/// walked recursively, so an unbounded depth overflows the stack and aborts the process.
const MAX_DEPTH: usize = 64;

/// A cell as POI types it. A formula cell is Blank here: the import reads neither kind.
#[derive(Debug, Clone, PartialEq)]
pub enum Cell {
    /// STRING; None when POI throws reading it (a shared-string index out of range).
    Text(Option<String>),
    /// NUMERIC; None when Double.parseDouble throws on the stored value.
    Number(Option<JavaDouble>),
    /// BLANK, BOOLEAN, ERROR or FORMULA.
    Other,
}

#[derive(Debug, Default)]
pub struct Sheet {
    rows: BTreeMap<u32, BTreeMap<u32, Cell>>,
}

impl Sheet {
    /// Sheet.getLastRowNum, -1 when empty.
    pub fn last_row(&self) -> i64 {
        self.rows.keys().next_back().map_or(-1, |r| i64::from(*r))
    }

    pub fn row(&self, index: u32) -> Option<&BTreeMap<u32, Cell>> {
        self.rows.get(&index)
    }
}

pub struct Workbook {
    sheets: Vec<(String, Sheet)>,
}

impl Workbook {
    /// Workbook.getSheet: the first sheet whose name equals this one ignoring case.
    pub fn sheet(&self, name: &str) -> Option<&Sheet> {
        self.sheets
            .iter()
            .find(|(n, _)| super::java_equals_ignore_case(n, name))
            .map(|(_, s)| s)
    }
}

/// Not a readable XLSX package.
#[derive(Debug)]
pub struct Invalid;

impl<E: std::error::Error> From<E> for Invalid {
    fn from(_: E) -> Self {
        Invalid
    }
}

#[derive(Debug, Default)]
struct Node {
    name: String,
    attrs: Vec<(String, String)>,
    children: Vec<Child>,
}

#[derive(Debug)]
enum Child {
    Node(Node),
    Text(String),
}

impl Node {
    fn attr(&self, name: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }

    fn nodes(&self) -> impl Iterator<Item = &Node> {
        self.children.iter().filter_map(|c| match c {
            Child::Node(n) => Some(n),
            Child::Text(_) => None,
        })
    }

    fn child(&self, name: &str) -> Option<&Node> {
        self.nodes().find(|n| n.name == name)
    }

    fn descendants<'a>(&'a self, name: &'a str, out: &mut Vec<&'a Node>) {
        for n in self.nodes() {
            if n.name == name {
                out.push(n);
            } else {
                n.descendants(name, out);
            }
        }
    }

    fn text(&self) -> String {
        self.children
            .iter()
            .filter_map(|c| match c {
                Child::Text(t) => Some(t.as_str()),
                Child::Node(_) => None,
            })
            .collect()
    }
}

fn element(e: &BytesStart<'_>) -> Result<Node, Invalid> {
    let mut attrs = Vec::new();
    for attr in e.attributes() {
        let attr = attr?;
        attrs.push((
            attr.key.local_name().as_ref().to_owned(),
            attr.normalized_value(XmlVersion::Implicit1_0)?.into_owned(),
        ));
    }
    Ok(Node {
        name: e.local_name().as_ref().to_owned(),
        attrs,
        children: Vec::new(),
    })
}

fn entity(name: &str) -> Option<char> {
    match name {
        "amp" => Some('&'),
        "lt" => Some('<'),
        "gt" => Some('>'),
        "quot" => Some('"'),
        "apos" => Some('\''),
        _ => None,
    }
}

/// The document element of an XML part.
fn parse_xml(bytes: &[u8]) -> Result<Node, Invalid> {
    let mut reader = Reader::from_reader(bytes);
    let mut stack: Vec<Node> = vec![Node::default()];
    let mut buf = Vec::new();
    let push_text = |stack: &mut Vec<Node>, text: String| {
        if let Some(top) = stack.last_mut() {
            match top.children.last_mut() {
                Some(Child::Text(t)) => t.push_str(&text),
                _ => top.children.push(Child::Text(text)),
            }
        }
    };
    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(e) => {
                if stack.len() > MAX_DEPTH {
                    return Err(Invalid);
                }
                stack.push(element(&e)?);
            }
            Event::Empty(e) => {
                let node = element(&e)?;
                stack
                    .last_mut()
                    .ok_or(Invalid)?
                    .children
                    .push(Child::Node(node));
            }
            Event::End(_) => {
                let node = stack.pop().ok_or(Invalid)?;
                stack
                    .last_mut()
                    .ok_or(Invalid)?
                    .children
                    .push(Child::Node(node));
            }
            Event::Text(t) => push_text(&mut stack, t.xml10_content().into_owned()),
            Event::CData(t) => push_text(&mut stack, t.into_inner().into_owned()),
            Event::GeneralRef(r) => {
                let c = match r.resolve_char_ref()? {
                    Some(c) => c,
                    None => entity(&r.into_inner()).ok_or(Invalid)?,
                };
                push_text(&mut stack, c.to_string());
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    let root = stack.pop().ok_or(Invalid)?;
    if !stack.is_empty() {
        return Err(Invalid);
    }
    root.children
        .into_iter()
        .find_map(|c| match c {
            Child::Node(n) => Some(n),
            Child::Text(_) => None,
        })
        .ok_or(Invalid)
}

struct Package {
    archive: zip::ZipArchive<Cursor<Vec<u8>>>,
    /// What is left of MAX_INFLATED_BYTES.
    room: u64,
}

impl Package {
    fn open(bytes: &[u8]) -> Result<Self, Invalid> {
        Ok(Self {
            archive: zip::ZipArchive::new(Cursor::new(bytes.to_vec()))?,
            room: MAX_INFLATED_BYTES,
        })
    }

    fn part(&mut self, path: &str) -> Result<Option<Node>, Invalid> {
        let mut entry = match self.archive.by_name(path) {
            Ok(entry) => entry,
            Err(zip::result::ZipError::FileNotFound) => return Ok(None),
            Err(_) => return Err(Invalid),
        };
        let mut bytes = Vec::new();
        (&mut entry).take(self.room + 1).read_to_end(&mut bytes)?;
        let inflated = bytes.len() as u64;
        if inflated > self.room {
            return Err(Invalid);
        }
        self.room -= inflated;
        parse_xml(&bytes).map(Some)
    }

    /// Relationship id to (type, resolved part path) of a part.
    fn relationships(
        &mut self,
        source: &str,
    ) -> Result<HashMap<String, (String, String)>, Invalid> {
        let (dir, file) = source.rsplit_once('/').unwrap_or(("", source));
        let rels_path = if dir.is_empty() {
            format!("_rels/{file}.rels")
        } else {
            format!("{dir}/_rels/{file}.rels")
        };
        let mut out = HashMap::new();
        if let Some(rels) = self.part(&rels_path)? {
            for rel in rels.nodes().filter(|n| n.name == "Relationship") {
                let (Some(id), Some(target)) = (rel.attr("Id"), rel.attr("Target")) else {
                    continue;
                };
                let kind = rel.attr("Type").unwrap_or_default().to_owned();
                out.insert(id.to_owned(), (kind, resolve(dir, target)));
            }
        }
        Ok(out)
    }
}

fn resolve(dir: &str, target: &str) -> String {
    let mut parts: Vec<&str> = if let Some(absolute) = target.strip_prefix('/') {
        return normalize(absolute.split('/').collect());
    } else {
        dir.split('/').filter(|p| !p.is_empty()).collect()
    };
    parts.extend(target.split('/'));
    normalize(parts)
}

fn normalize(parts: Vec<&str>) -> String {
    let mut out: Vec<&str> = Vec::new();
    for p in parts {
        match p {
            "" | "." => {}
            ".." => {
                out.pop();
            }
            p => out.push(p),
        }
    }
    out.join("/")
}

/// XSSFRichTextString.utfDecode: _xHHHH_ escapes back to their character.
fn utf_decode(value: String) -> String {
    if !value.contains("_x") {
        return value;
    }
    let mut out = String::with_capacity(value.len());
    let mut rest = value.as_str();
    while let Some(i) = rest.find("_x") {
        let candidate = rest.get(i + 2..i + 7);
        match candidate {
            Some(c) if c.ends_with('_') && c[..4].bytes().all(|b| b.is_ascii_hexdigit()) => {
                out.push_str(&rest[..i]);
                let code = u32::from_str_radix(&c[..4], 16).unwrap_or(0xFFFD);
                out.push(char::from_u32(code).unwrap_or('\u{FFFD}'));
                rest = &rest[i + 7..];
            }
            _ => {
                out.push_str(&rest[..i + 2]);
                rest = &rest[i + 2..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// XSSFRichTextString.getString: the plain text, or the runs' texts joined (phonetic runs aside).
fn rich_text(node: &Node) -> String {
    let runs: Vec<&Node> = node.nodes().filter(|n| n.name == "r").collect();
    let text = if runs.is_empty() {
        node.child("t").map(Node::text).unwrap_or_default()
    } else {
        runs.iter()
            .filter_map(|r| r.child("t"))
            .map(Node::text)
            .collect()
    };
    utf_decode(text)
}

/// CellReference column index of "AB12".
fn column_of(reference: &str) -> Option<u32> {
    let letters: String = reference
        .trim_start_matches('$')
        .chars()
        .take_while(char::is_ascii_alphabetic)
        .collect();
    if letters.is_empty() {
        return None;
    }
    let mut col: u32 = 0;
    for c in letters.chars() {
        col = col
            .checked_mul(26)?
            .checked_add(u32::from(c.to_ascii_uppercase() as u8 - b'A') + 1)?;
    }
    Some(col - 1)
}

fn cell(c: &Node, shared: &[String]) -> Cell {
    if c.child("f").is_some() {
        return Cell::Other;
    }
    let v = c.child("v").map(Node::text);
    match c.attr("t").unwrap_or("n") {
        "n" => match v {
            Some(v) => Cell::Number(java_double::parse(&v)),
            None => Cell::Other,
        },
        "s" => Cell::Text(
            v.and_then(|v| v.trim().parse::<usize>().ok())
                .and_then(|i| shared.get(i).cloned()),
        ),
        "inlineStr" => Cell::Text(Some(c.child("is").map(rich_text).unwrap_or_default())),
        "str" => Cell::Text(Some(v.unwrap_or_default())),
        _ => Cell::Other,
    }
}

fn sheet(node: &Node, shared: &[String]) -> Sheet {
    let mut rows = BTreeMap::new();
    let Some(data) = node.child("sheetData") else {
        return Sheet::default();
    };
    let mut next_row: u32 = 0;
    for row in data.nodes().filter(|n| n.name == "row") {
        let index = row
            .attr("r")
            .and_then(|r| r.trim().parse::<u32>().ok())
            .and_then(|r| r.checked_sub(1))
            .unwrap_or(next_row);
        next_row = index.saturating_add(1);
        let mut cells = BTreeMap::new();
        let mut next_col: u32 = 0;
        for c in row.nodes().filter(|n| n.name == "c") {
            let col = c.attr("r").and_then(column_of).unwrap_or(next_col);
            next_col = col.saturating_add(1);
            cells.insert(col, cell(c, shared));
        }
        rows.insert(index, cells);
    }
    Sheet { rows }
}

const OFFICE_DOCUMENT: &str = "/officeDocument";

/// Reads an XLSX workbook (WorkbookFactory.create for the OOXML format).
pub fn read(bytes: &[u8]) -> Result<Workbook, Invalid> {
    let mut package = Package::open(bytes)?;
    let root = package.relationships("")?;
    let workbook_path = root
        .values()
        .find(|(kind, _)| kind.ends_with(OFFICE_DOCUMENT))
        .map(|(_, path)| path.clone())
        .ok_or(Invalid)?;
    let workbook = package.part(&workbook_path)?.ok_or(Invalid)?;
    if workbook.name != "workbook" {
        return Err(Invalid);
    }
    let rels = package.relationships(&workbook_path)?;
    let shared = match rels
        .values()
        .find(|(kind, _)| kind.ends_with("/sharedStrings"))
    {
        Some((_, path)) => match package.part(&path.clone())? {
            Some(sst) => sst
                .nodes()
                .filter(|n| n.name == "si")
                .map(rich_text)
                .collect(),
            None => Vec::new(),
        },
        None => Vec::new(),
    };
    let mut entries = Vec::new();
    if let Some(list) = workbook.child("sheets") {
        let mut found = Vec::new();
        list.descendants("sheet", &mut found);
        for s in found {
            let name = s.attr("name").unwrap_or_default().to_owned();
            let path = s
                .attr("id")
                .and_then(|id| rels.get(id))
                .map(|(_, p)| p.clone());
            entries.push((name, path));
        }
    }
    let mut sheets = Vec::with_capacity(entries.len());
    for (name, path) in entries {
        let parsed = match path {
            Some(path) => match package.part(&path)? {
                Some(node) => sheet(&node, &shared),
                None => return Err(Invalid),
            },
            None => return Err(Invalid),
        };
        sheets.push((name, parsed));
    }
    Ok(Workbook { sheets })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_escapes_and_columns() {
        assert_eq!(utf_decode("a_x0041_b_x12".into()), "aAb_x12");
        assert_eq!(column_of("A1"), Some(0));
        assert_eq!(column_of("AB12"), Some(27));
        assert_eq!(
            resolve("xl", "worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml"
        );
        assert_eq!(resolve("xl", "/xl/worksheets/s.xml"), "xl/worksheets/s.xml");
        assert_eq!(resolve("xl/a", "../b.xml"), "xl/b.xml");
    }

    #[test]
    fn rejects_what_is_not_a_package() {
        assert!(read(b"not a spreadsheet").is_err());
    }

    #[test]
    fn a_part_nested_deeper_than_any_workbook_is_refused() {
        let depth = 200_000;
        let nested = format!("{}{}", "<a>".repeat(depth), "</a>".repeat(depth));
        assert!(parse_xml(nested.as_bytes()).is_err());
        let workbook_like = format!("{}{}", "<a>".repeat(12), "</a>".repeat(12));
        assert!(parse_xml(workbook_like.as_bytes()).is_ok());
    }

    fn package(parts: &[(&str, &[u8])]) -> Vec<u8> {
        use std::io::Write;
        let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
        for (name, content) in parts {
            zip.start_file(*name, zip::write::SimpleFileOptions::default())
                .expect("start the entry");
            zip.write_all(content).expect("write the entry");
        }
        zip.finish().expect("finish the archive").into_inner()
    }

    #[test]
    fn the_parts_of_a_package_share_one_inflated_size_budget() {
        let filler = format!("<a>{}</a>", "x".repeat(20 * 1024 * 1024));
        let bytes = package(&[
            ("_rels/.rels", filler.as_bytes()),
            ("xl/workbook.xml", filler.as_bytes()),
        ]);
        let mut package = Package::open(&bytes).expect("a zip archive");
        assert!(package.part("_rels/.rels").is_ok());
        assert!(package.part("xl/workbook.xml").is_err());
    }
}

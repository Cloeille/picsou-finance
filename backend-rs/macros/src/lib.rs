//! Procedural macros for Picsou's dual-engine (`PostgreSQL` + `SQLite`) database layer.
//!
//! Exports:
//! - `#[derive(DbFromRow)]`: generates `DbFromRow` for named and tuple structs, supporting both
//!   `#[db(...)]` and `#[sqlx(...)]` attributes (`rename = "..."`, `default`, `flatten`, `skip`).
//! - `#[derive(DbEnum)]`: generates `DbBind` (owned & borrowed) and `DbDecode` for unit enums
//!   that also derive `sqlx::Type`, honoring `#[sqlx(rename_all = "...")]` / `#[db(rename_all = "...")]`
//!   and per-variant `rename = "..."`.

use proc_macro::TokenStream;
use proc_macro2::{Span, TokenStream as TokenStream2};
use quote::quote;
use syn::parse::ParseStream;
use syn::punctuated::Punctuated;
use syn::{Attribute, Data, DeriveInput, Fields, Ident, LitStr, Meta, Token, parse_macro_input};

fn db_crate_path(explicit: Option<syn::Path>) -> TokenStream2 {
    if let Some(path) = explicit {
        return quote!(#path);
    }
    match std::env::var("CARGO_CRATE_NAME").as_deref() {
        Ok("picsou") => quote!(crate::db),
        _ => quote!(::picsou::db),
    }
}

#[derive(Default)]
struct StructOpts {
    default_all: bool,
    crate_path: Option<syn::Path>,
}

#[derive(Default)]
struct FieldOpts {
    rename: Option<String>,
    default: bool,
    flatten: bool,
    skip: bool,
}

fn parse_struct_opts(attrs: &[Attribute]) -> syn::Result<StructOpts> {
    let mut opts = StructOpts::default();
    for attr in attrs {
        if !attr.path().is_ident("db") && !attr.path().is_ident("sqlx") {
            continue;
        }
        let nested = attr.parse_args_with(Punctuated::<Meta, Token![,]>::parse_terminated)?;
        for meta in nested {
            match meta {
                Meta::Path(p) if p.is_ident("default") => {
                    opts.default_all = true;
                }
                Meta::NameValue(nv) if nv.path.is_ident("crate") => {
                    let lit: LitStr = syn::parse2(quote!(#nv.value)).or_else(|_| {
                        if let syn::Expr::Lit(syn::ExprLit {
                            lit: syn::Lit::Str(s),
                            ..
                        }) = &nv.value
                        {
                            Ok(s.clone())
                        } else {
                            Err(syn::Error::new_spanned(
                                &nv.value,
                                "expected string literal for crate path",
                            ))
                        }
                    })?;
                    opts.crate_path = Some(lit.parse::<syn::Path>()?);
                }
                // Ignore unknown sqlx-only container attributes gracefully if present.
                _ => {}
            }
        }
    }
    Ok(opts)
}

fn parse_field_opts(attrs: &[Attribute]) -> syn::Result<FieldOpts> {
    let mut opts = FieldOpts::default();
    for attr in attrs {
        if !attr.path().is_ident("db") && !attr.path().is_ident("sqlx") {
            continue;
        }
        let nested = attr.parse_args_with(Punctuated::<Meta, Token![,]>::parse_terminated)?;
        for meta in nested {
            match meta {
                Meta::Path(p) if p.is_ident("default") => {
                    opts.default = true;
                }
                Meta::Path(p) if p.is_ident("flatten") => {
                    opts.flatten = true;
                }
                Meta::Path(p) if p.is_ident("skip") => {
                    opts.skip = true;
                }
                Meta::NameValue(nv) if nv.path.is_ident("rename") => {
                    if let syn::Expr::Lit(syn::ExprLit {
                        lit: syn::Lit::Str(s),
                        ..
                    }) = &nv.value
                    {
                        opts.rename = Some(s.value());
                    } else {
                        return Err(syn::Error::new_spanned(
                            &nv.value,
                            "expected string literal for `rename`",
                        ));
                    }
                }
                other => {
                    return Err(syn::Error::new_spanned(
                        other,
                        "unsupported #[db(...)] / #[sqlx(...)] field attribute in DbFromRow",
                    ));
                }
            }
        }
    }
    if opts.flatten && opts.skip {
        return Err(syn::Error::new(
            Span::call_site(),
            "`flatten` and `skip` cannot be combined on the same field",
        ));
    }
    if opts.flatten && opts.rename.is_some() {
        return Err(syn::Error::new(
            Span::call_site(),
            "`flatten` and `rename` cannot be combined on the same field",
        ));
    }
    Ok(opts)
}

/// Derives `DbFromRow` for a struct so it can be decoded from both PostgreSQL and SQLite rows.
///
/// Supported attributes (either `#[db(...)]` or `#[sqlx(...)]`):
/// - `rename = "column_name"`
/// - `default` (on a field or on the struct; falls back to `Default::default()` if the column is absent)
/// - `flatten` (delegates to the field type's `DbFromRow` impl)
/// - `skip` (populates with `Default::default()` without reading from the row)
#[proc_macro_derive(DbFromRow, attributes(db, sqlx))]
pub fn derive_db_from_row(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    match expand_db_from_row(input) {
        Ok(ts) => ts.into(),
        Err(err) => err.to_compile_error().into(),
    }
}

fn expand_db_from_row(input: DeriveInput) -> syn::Result<TokenStream2> {
    let ident = &input.ident;
    let struct_opts = parse_struct_opts(&input.attrs)?;
    let db = db_crate_path(struct_opts.crate_path);
    let (impl_generics, ty_generics, where_clause) = input.generics.split_for_impl();

    let Data::Struct(data_struct) = &input.data else {
        return Err(syn::Error::new_spanned(
            ident,
            "#[derive(DbFromRow)] only supports structs",
        ));
    };

    let body = match &data_struct.fields {
        Fields::Named(fields) => {
            let mut inits = Vec::with_capacity(fields.named.len());
            for field in &fields.named {
                let field_ident = field.ident.as_ref().expect("named field");
                let field_ty = &field.ty;
                let fopts = parse_field_opts(&field.attrs)?;
                let use_default = fopts.default || struct_opts.default_all;

                let expr = if fopts.skip {
                    quote!(::core::default::Default::default())
                } else if fopts.flatten {
                    if use_default {
                        quote! {
                            match <#field_ty as #db::DbFromRow>::from_row(row) {
                                ::core::result::Result::Ok(v) => v,
                                ::core::result::Result::Err(::sqlx::Error::ColumnNotFound(_)) => {
                                    ::core::default::Default::default()
                                }
                                ::core::result::Result::Err(e) => return ::core::result::Result::Err(e),
                            }
                        }
                    } else {
                        quote!(<#field_ty as #db::DbFromRow>::from_row(row)?)
                    }
                } else {
                    let raw_name = field_ident.to_string();
                    let default_col = raw_name.strip_prefix("r#").unwrap_or(&raw_name);
                    let col_name = fopts.rename.as_deref().unwrap_or(default_col);
                    if use_default {
                        quote! {
                            match row.try_get::<#field_ty, _>(#col_name) {
                                ::core::result::Result::Ok(v) => v,
                                ::core::result::Result::Err(::sqlx::Error::ColumnNotFound(_)) => {
                                    ::core::default::Default::default()
                                }
                                ::core::result::Result::Err(e) => return ::core::result::Result::Err(e),
                            }
                        }
                    } else {
                        quote!(row.try_get::<#field_ty, _>(#col_name)?)
                    }
                };
                inits.push(quote!(#field_ident: #expr));
            }
            quote! {
                ::core::result::Result::Ok(Self {
                    #(#inits),*
                })
            }
        }
        Fields::Unnamed(fields) => {
            let mut inits = Vec::with_capacity(fields.unnamed.len());
            let mut col_idx = 0usize;
            for field in &fields.unnamed {
                let field_ty = &field.ty;
                let fopts = parse_field_opts(&field.attrs)?;
                let use_default = fopts.default || struct_opts.default_all;

                let expr = if fopts.skip {
                    quote!(::core::default::Default::default())
                } else if fopts.flatten {
                    quote!(<#field_ty as #db::DbFromRow>::from_row(row)?)
                } else {
                    let idx = col_idx;
                    col_idx += 1;
                    if use_default {
                        quote! {
                            match row.try_get::<#field_ty, _>(#idx) {
                                ::core::result::Result::Ok(v) => v,
                                ::core::result::Result::Err(::sqlx::Error::ColumnIndexOutOfBounds { .. }) => {
                                    ::core::default::Default::default()
                                }
                                ::core::result::Result::Err(e) => return ::core::result::Result::Err(e),
                            }
                        }
                    } else {
                        quote!(row.try_get::<#field_ty, _>(#idx)?)
                    }
                };
                inits.push(expr);
            }
            quote! {
                ::core::result::Result::Ok(Self(#(#inits),*))
            }
        }
        Fields::Unit => {
            quote!(::core::result::Result::Ok(Self))
        }
    };

    Ok(quote! {
        impl #impl_generics #db::DbFromRow for #ident #ty_generics #where_clause {
            fn from_row(row: &#db::DbRow) -> ::core::result::Result<Self, ::sqlx::Error> {
                #body
            }
        }
    })
}

#[derive(Clone, Copy)]
enum RenameRule {
    None,
    Uppercase,
    Lowercase,
    ScreamingSnakeCase,
    SnakeCase,
    CamelCase,
    PascalCase,
    KebabCase,
}

impl RenameRule {
    fn parse(s: &str, span: Span) -> syn::Result<Self> {
        match s {
            "UPPERCASE" => Ok(Self::Uppercase),
            "lowercase" => Ok(Self::Lowercase),
            "SCREAMING_SNAKE_CASE" => Ok(Self::ScreamingSnakeCase),
            "snake_case" => Ok(Self::SnakeCase),
            "camelCase" => Ok(Self::CamelCase),
            "PascalCase" => Ok(Self::PascalCase),
            "kebab-case" => Ok(Self::KebabCase),
            other => Err(syn::Error::new(
                span,
                format!("unsupported rename_all rule `{other}`"),
            )),
        }
    }

    fn apply(self, ident: &str) -> String {
        match self {
            Self::None | Self::PascalCase => ident.to_owned(),
            Self::Uppercase => ident.to_ascii_uppercase(),
            Self::Lowercase => ident.to_ascii_lowercase(),
            Self::SnakeCase => to_snake(ident),
            Self::ScreamingSnakeCase => to_snake(ident).to_ascii_uppercase(),
            Self::KebabCase => to_snake(ident).replace('_', "-"),
            Self::CamelCase => {
                let mut chars = ident.chars();
                match chars.next() {
                    None => String::new(),
                    Some(first) => first.to_ascii_lowercase().to_string() + chars.as_str(),
                }
            }
        }
    }
}

fn to_snake(ident: &str) -> String {
    let mut out = String::with_capacity(ident.len() + 4);
    for (i, ch) in ident.chars().enumerate() {
        if ch.is_ascii_uppercase() {
            if i > 0 {
                out.push('_');
            }
            out.push(ch.to_ascii_lowercase());
        } else {
            out.push(ch);
        }
    }
    out
}

#[derive(Default)]
struct EnumOpts {
    rename_all: Option<RenameRule>,
    crate_path: Option<syn::Path>,
}

fn parse_enum_opts(attrs: &[Attribute]) -> syn::Result<EnumOpts> {
    let mut opts = EnumOpts::default();
    for attr in attrs {
        if !attr.path().is_ident("db") && !attr.path().is_ident("sqlx") {
            continue;
        }
        let nested = attr.parse_args_with(|input: ParseStream<'_>| {
            Punctuated::<Meta, Token![,]>::parse_terminated(input)
        })?;
        for meta in nested {
            if let Meta::NameValue(nv) = meta {
                if nv.path.is_ident("rename_all") {
                    if let syn::Expr::Lit(syn::ExprLit {
                        lit: syn::Lit::Str(s),
                        ..
                    }) = &nv.value
                    {
                        opts.rename_all = Some(RenameRule::parse(&s.value(), s.span())?);
                    }
                } else if nv.path.is_ident("crate")
                    && let syn::Expr::Lit(syn::ExprLit {
                        lit: syn::Lit::Str(s),
                        ..
                    }) = &nv.value
                {
                    opts.crate_path = Some(s.parse::<syn::Path>()?);
                }
            }
        }
    }
    Ok(opts)
}

fn parse_variant_rename(attrs: &[Attribute]) -> syn::Result<Option<String>> {
    for attr in attrs {
        if !attr.path().is_ident("db") && !attr.path().is_ident("sqlx") {
            continue;
        }
        let nested = attr.parse_args_with(Punctuated::<Meta, Token![,]>::parse_terminated)?;
        for meta in nested {
            if let Meta::NameValue(nv) = meta
                && nv.path.is_ident("rename")
                && let syn::Expr::Lit(syn::ExprLit {
                    lit: syn::Lit::Str(s),
                    ..
                }) = &nv.value
            {
                return Ok(Some(s.value()));
            }
        }
    }
    Ok(None)
}

/// Derives `DbBind` (owned & borrowed) and `DbDecode` for unit enums that also derive `sqlx::Type`.
///
/// On PostgreSQL, delegates directly to `sqlx::Encode<Postgres>` / `sqlx::Decode<Postgres>`.
/// On SQLite, binds and decodes canonical `TEXT` labels honoring `#[sqlx(rename_all = "...")]`
/// and `#[db(rename_all = "...")]` / `rename = "..."`.
#[proc_macro_derive(DbEnum, attributes(db, sqlx))]
pub fn derive_db_enum(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    match expand_db_enum(input) {
        Ok(ts) => ts.into(),
        Err(err) => err.to_compile_error().into(),
    }
}

fn expand_db_enum(input: DeriveInput) -> syn::Result<TokenStream2> {
    let ident = &input.ident;
    let opts = parse_enum_opts(&input.attrs)?;
    let db = db_crate_path(opts.crate_path);
    let rule = opts.rename_all.unwrap_or(RenameRule::None);

    let Data::Enum(data_enum) = &input.data else {
        return Err(syn::Error::new_spanned(
            ident,
            "#[derive(DbEnum)] only supports unit enums",
        ));
    };

    let mut to_arms = Vec::with_capacity(data_enum.variants.len());
    let mut from_arms = Vec::with_capacity(data_enum.variants.len());
    let enum_name = ident.to_string();

    for variant in &data_enum.variants {
        if !matches!(variant.fields, Fields::Unit) {
            return Err(syn::Error::new_spanned(
                variant,
                "#[derive(DbEnum)] only supports unit enum variants",
            ));
        }
        let v_ident: &Ident = &variant.ident;
        let label = match parse_variant_rename(&variant.attrs)? {
            Some(explicit) => explicit,
            None => rule.apply(&v_ident.to_string()),
        };
        to_arms.push(quote!(Self::#v_ident => #label));
        from_arms.push(quote!(#label => ::core::result::Result::Ok(Self::#v_ident)));
    }

    Ok(quote! {
        impl #db::DbBind for #ident {
            fn bind_pg(
                self,
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, self)
            }

            fn bind_null_pg(
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, ::core::option::Option::<Self>::None)
            }

            fn to_sqlite_value(&self) -> ::rusqlite::types::Value {
                let s: &'static str = match self {
                    #(#to_arms),*
                };
                ::rusqlite::types::Value::Text(s.to_owned())
            }
        }

        impl #db::DbBind for &#ident {
            fn bind_pg(
                self,
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, self)
            }

            fn bind_null_pg(
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, ::core::option::Option::<#ident>::None)
            }

            fn to_sqlite_value(&self) -> ::rusqlite::types::Value {
                <#ident as #db::DbBind>::to_sqlite_value(*self)
            }
        }

        impl #db::DbDecode for #ident {
            fn decode_pg<I>(
                row: &::sqlx::postgres::PgRow,
                index: I,
            ) -> ::core::result::Result<Self, ::sqlx::Error>
            where
                I: ::sqlx::ColumnIndex<::sqlx::postgres::PgRow>,
            {
                ::sqlx::Row::try_get::<Self, _>(row, index)
            }

            fn decode_pg_opt<I>(
                row: &::sqlx::postgres::PgRow,
                index: I,
            ) -> ::core::result::Result<::core::option::Option<Self>, ::sqlx::Error>
            where
                I: ::sqlx::ColumnIndex<::sqlx::postgres::PgRow>,
            {
                ::sqlx::Row::try_get::<::core::option::Option<Self>, _>(row, index)
            }

            fn decode_sqlite(
                value: &::rusqlite::types::Value,
            ) -> ::core::result::Result<Self, ::sqlx::Error> {
                match value {
                    ::rusqlite::types::Value::Text(raw) => match raw.as_str() {
                        #(#from_arms,)*
                        other => ::core::result::Result::Err(::sqlx::Error::Decode(
                            ::std::boxed::Box::new(::std::io::Error::new(
                                ::std::io::ErrorKind::InvalidData,
                                ::std::format!(
                                    "unknown {} value {:?}",
                                    #enum_name,
                                    other
                                ),
                            )),
                        )),
                    },
                    other => ::core::result::Result::Err(::sqlx::Error::Decode(
                        ::std::boxed::Box::new(::std::io::Error::new(
                            ::std::io::ErrorKind::InvalidData,
                            ::std::format!(
                                "expected SQLite TEXT for {}, got {:?}",
                                #enum_name,
                                other
                            ),
                        )),
                    )),
                }
            }
        }
    })
}

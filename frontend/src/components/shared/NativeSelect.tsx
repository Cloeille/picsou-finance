import React, { Children, isValidElement, type ComponentProps, type ReactNode } from 'react'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'

const EMPTY_VALUE = '__PICSOU_SELECT_EMPTY__'

type ParsedOption = {
  kind: 'option'
  value: string
  label: string
  disabled?: boolean
}

type ParsedGroup = {
  kind: 'group'
  label: string
  options: ParsedOption[]
}

type ParsedNode = ParsedOption | ParsedGroup

function extractText(node: ReactNode): string {
  if (node == null || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(extractText).join('')
  if (isValidElement<{ children?: ReactNode }>(node)) return extractText(node.props.children)
  return ''
}

function parseSelectChildren(children: ReactNode): ParsedNode[] {
  const out: ParsedNode[] = []
  Children.forEach(children, (child) => {
    if (!isValidElement(child)) return
    if (child.type === React.Fragment) {
      const props = child.props as { children?: ReactNode }
      out.push(...parseSelectChildren(props.children))
      return
    }
    if (child.type === 'option') {
      const props = child.props as ComponentProps<'option'>
      const label = extractText(props.children)
      const value = props.value != null ? String(props.value) : label
      out.push({ kind: 'option', value, label, disabled: props.disabled })
      return
    }
    if (child.type === 'optgroup') {
      const props = child.props as ComponentProps<'optgroup'>
      const nested = parseSelectChildren(props.children).filter(
        (n): n is ParsedOption => n.kind === 'option',
      )
      out.push({ kind: 'group', label: props.label ?? '', options: nested })
    }
  })
  return out
}

function encodeValue(val: string): string {
  return val === '' ? EMPTY_VALUE : val
}

function decodeValue(val: string): string {
  return val === EMPTY_VALUE ? '' : val
}

/**
 * In-app custom dropdown backed by Radix UI Select, while keeping a drop-in `<select>` API
 * (`value`, `onChange`, `<option>` / `<optgroup>` children) so every form across Picsou renders
 * a consistent custom menu instead of the OS native popup.
 */
export function NativeSelect({
  className,
  children,
  value,
  defaultValue,
  onChange,
  disabled,
  id,
  name,
  required,
  'aria-label': ariaLabel,
  'aria-labelledby': ariaLabelledBy,
  'aria-invalid': ariaInvalid,
}: ComponentProps<'select'>) {
  const parsed = parseSelectChildren(children)

  const stringValue = value !== undefined ? String(value) : undefined
  const stringDefault = defaultValue !== undefined ? String(defaultValue) : undefined

  // Render separator for disabled divider options like "──────────"
  const renderOption = (opt: ParsedOption, idx: number) => {
    if (opt.disabled && /^[─\-—\s]+$/.test(opt.label)) {
      return <SelectSeparator key={`sep-${idx}`} />
    }
    return (
      <SelectItem
        key={`${opt.value}-${idx}`}
        value={encodeValue(opt.value)}
        data-option-value={opt.value}
        disabled={opt.disabled}
      >
        {opt.label}
      </SelectItem>
    )
  }

  return (
    <Select
      value={stringValue !== undefined ? encodeValue(stringValue) : undefined}
      defaultValue={stringDefault !== undefined ? encodeValue(stringDefault) : undefined}
      disabled={disabled}
      name={name}
      required={required}
      onValueChange={(next) => {
        const decoded = decodeValue(next)
        onChange?.({
          target: { value: decoded, name: name ?? '' },
          currentTarget: { value: decoded, name: name ?? '' },
        } as unknown as React.ChangeEvent<HTMLSelectElement>)
      }}
    >
      <SelectTrigger
        id={id}
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledBy}
        aria-invalid={ariaInvalid}
        data-value={stringValue ?? ''}
        className={cn(className)}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {parsed.map((node, idx) =>
          node.kind === 'group' ? (
            <SelectGroup key={`grp-${node.label}-${idx}`}>
              <SelectLabel>{node.label}</SelectLabel>
              {node.options.map((opt, j) => renderOption(opt, j))}
            </SelectGroup>
          ) : (
            renderOption(node, idx)
          ),
        )}
      </SelectContent>
    </Select>
  )
}

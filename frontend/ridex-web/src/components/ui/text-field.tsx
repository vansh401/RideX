import { Eye, EyeOff } from 'lucide-react'
import { forwardRef, useId, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { Input } from './input'
import { Label } from './label'

interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  hint?: string
  /** Optional element shown to the right of the label, e.g. a "Forgot password?" link */
  labelAside?: ReactNode
}

/** Label + input + hint + error, wired together for screen readers. */
export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(
  ({ label, error, hint, labelAside, id: idProp, ...props }, ref) => {
    const autoId = useId()
    const id = idProp ?? autoId
    const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
    return (
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between gap-2">
          <Label htmlFor={id}>{label}</Label>
          {labelAside}
        </div>
        <Input ref={ref} id={id} aria-invalid={error ? true : undefined} aria-describedby={describedBy} {...props} />
        {error ? (
          <p id={`${id}-error`} className="text-sm text-danger">
            {error}
          </p>
        ) : hint ? (
          <p id={`${id}-hint`} className="text-sm text-fg-muted">
            {hint}
          </p>
        ) : null}
      </div>
    )
  },
)
TextField.displayName = 'TextField'

/** Same as TextField with a show/hide toggle. */
export const PasswordField = forwardRef<HTMLInputElement, Omit<TextFieldProps, 'type'>>(
  ({ label, error, hint, labelAside, id: idProp, ...props }, ref) => {
    const autoId = useId()
    const id = idProp ?? autoId
    const [visible, setVisible] = useState(false)
    const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
    return (
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between gap-2">
          <Label htmlFor={id}>{label}</Label>
          {labelAside}
        </div>
        <div className="relative">
          <Input
            ref={ref}
            id={id}
            type={visible ? 'text' : 'password'}
            className="pr-12"
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
            {...props}
          />
          <button
            type="button"
            onClick={() => setVisible((v) => !v)}
            aria-label={visible ? 'Hide password' : 'Show password'}
            aria-pressed={visible}
            className="absolute right-1 top-1/2 grid size-10 -translate-y-1/2 place-items-center rounded-md text-fg-muted hover:text-fg"
          >
            {visible ? <EyeOff className="size-4" aria-hidden /> : <Eye className="size-4" aria-hidden />}
          </button>
        </div>
        {error ? (
          <p id={`${id}-error`} className="text-sm text-danger">
            {error}
          </p>
        ) : hint ? (
          <p id={`${id}-hint`} className="text-sm text-fg-muted">
            {hint}
          </p>
        ) : null}
      </div>
    )
  },
)
PasswordField.displayName = 'PasswordField'

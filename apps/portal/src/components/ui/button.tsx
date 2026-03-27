'use client'

import { Button as UiButton } from '@sphereon/ui-react'
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'

interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> {
  variant?: 'primary' | 'secondary' | 'outline'
  size?: 'default' | 'large'
  children?: ReactNode
  onClick?: () => void
}

const sizeMap = { default: 'md', large: 'lg' } as const

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  function Button({ variant = 'primary', size = 'default', disabled, onClick, children, className, ...rest }, ref) {
    return (
      <UiButton
        ref={ref}
        variant={variant}
        size={sizeMap[size]}
        isDisabled={disabled}
        onClick={onClick}
        className={className}
        {...rest}
      >
        {children}
      </UiButton>
    )
  },
)

import clsx from 'clsx'
import styles from './spinner.module.css'

interface SpinnerProps {
  size?: 'small' | 'medium' | 'large'
}

export function Spinner({ size = 'medium' }: SpinnerProps) {
  return <div className={clsx(styles.spinner, styles[size])} />
}

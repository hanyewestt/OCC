import * as React from 'react'
import { ScrollArea as ScrollAreaPrimitive } from 'radix-ui'
import { cn } from '@/lib/utils'

type ScrollAreaProps = React.ComponentProps<typeof ScrollAreaPrimitive.Root> & {
  viewportProps?: React.ComponentProps<typeof ScrollAreaPrimitive.Viewport>
}

function ScrollArea({ className, children, viewportProps, type = 'auto', ...props }: ScrollAreaProps) {
  return <ScrollAreaPrimitive.Root data-slot="scroll-area" type={type} className={cn('relative overflow-hidden', className)} {...props}>
    <ScrollAreaPrimitive.Viewport {...viewportProps} data-slot="scroll-area-viewport"
      className={cn('size-full max-h-[inherit] rounded-[inherit] outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2', viewportProps?.className)}>
      {children}
    </ScrollAreaPrimitive.Viewport>
    <ScrollBar />
    <ScrollAreaPrimitive.Corner />
  </ScrollAreaPrimitive.Root>
}

function ScrollBar({ className, orientation = 'vertical', ...props }: React.ComponentProps<typeof ScrollAreaPrimitive.Scrollbar>) {
  return <ScrollAreaPrimitive.Scrollbar data-slot="scroll-area-scrollbar" orientation={orientation}
    className={cn('flex touch-none select-none transition-colors', orientation === 'vertical' ? 'h-full w-2.5 border-l border-l-transparent p-px' : 'h-2.5 flex-col border-t border-t-transparent p-px', className)}
    {...props}>
    <ScrollAreaPrimitive.Thumb data-slot="scroll-area-thumb" className="relative flex-1 rounded-full bg-muted-foreground/30 hover:bg-muted-foreground/50 transition-colors" />
  </ScrollAreaPrimitive.Scrollbar>
}

export { ScrollArea, ScrollBar }

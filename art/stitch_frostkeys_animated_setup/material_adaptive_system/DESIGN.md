---
name: Material Adaptive System
colors:
  surface: '#fdf8fd'
  surface-dim: '#ddd9de'
  surface-bright: '#fdf8fd'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f7f2f8'
  surface-container: '#f1ecf2'
  surface-container-high: '#ebe7ec'
  surface-container-highest: '#e5e1e7'
  on-surface: '#1c1b1f'
  on-surface-variant: '#494551'
  inverse-surface: '#313034'
  inverse-on-surface: '#f4eff5'
  outline: '#7a7582'
  outline-variant: '#cbc4d2'
  surface-tint: '#6750a4'
  primary: '#4f378a'
  on-primary: '#ffffff'
  primary-container: '#6750a4'
  on-primary-container: '#e0d2ff'
  inverse-primary: '#cfbcff'
  secondary: '#625b71'
  on-secondary: '#ffffff'
  secondary-container: '#e8def9'
  on-secondary-container: '#686177'
  tertiary: '#633b48'
  on-tertiary: '#ffffff'
  tertiary-container: '#7d5260'
  on-tertiary-container: '#ffcbda'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e9ddff'
  primary-fixed-dim: '#cfbcff'
  on-primary-fixed: '#22005d'
  on-primary-fixed-variant: '#4f378a'
  secondary-fixed: '#e8def9'
  secondary-fixed-dim: '#ccc2dc'
  on-secondary-fixed: '#1e192b'
  on-secondary-fixed-variant: '#4a4358'
  tertiary-fixed: '#ffd9e3'
  tertiary-fixed-dim: '#eeb8c8'
  on-tertiary-fixed: '#31111d'
  on-tertiary-fixed-variant: '#633b48'
  background: '#fdf8fd'
  on-background: '#1c1b1f'
  surface-variant: '#e5e1e7'
typography:
  display-lg:
    fontFamily: robotoFlex
    fontSize: 57px
    fontWeight: '400'
    lineHeight: 64px
    letterSpacing: -0.25px
  headline-lg:
    fontFamily: robotoFlex
    fontSize: 32px
    fontWeight: '400'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: robotoFlex
    fontSize: 28px
    fontWeight: '400'
    lineHeight: 36px
  title-lg:
    fontFamily: robotoFlex
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 28px
  body-lg:
    fontFamily: robotoFlex
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.5px
  body-md:
    fontFamily: robotoFlex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.25px
  label-lg:
    fontFamily: robotoFlex
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.1px
  label-md:
    fontFamily: robotoFlex
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.5px
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 24px
---

## Brand & Style

The design system is centered on the principles of **Material Design 3**, emphasizing a friendly, expressive, and deeply personal user experience. It is designed to feel alive and responsive to the user's context, evoking an emotional response of warmth and accessibility.

The aesthetic leans into **Modern Corporate** with a heavy influence from the **Material You** movement. This means prioritizing clarity and utility while allowing for playful, expressive moments through high-quality variable typography and organic shapes. The design system rejects the rigidity of traditional enterprise software in favor of a soft, approachable interface that feels tailored to the individual.

## Colors

This design system utilizes a dynamic tonal palette logic. The colors are categorized into functional roles:

- **Primary:** The signature color used for key action components like Floating Action Buttons (FABs) and prominent states.
- **Secondary:** Used for less prominent components in the UI, providing a balanced, harmonic contrast.
- **Tertiary:** Used for accents or to distinguish specific sub-sections, adding a layer of expressive variety.
- **Neutral:** Used for surfaces, backgrounds, and high-emphasis text.

The system follows a "Tonal Container" approach where elements are often placed inside containers that use a lighter or more muted version of the role color (e.g., Primary Container, Secondary Container) to create hierarchy without relying solely on heavy shadows.

## Typography

The typography is powered by **Roboto Flex**, a highly adaptable variable font that provides the structural integrity required for a system-level font while offering the expressive range of the "Google Sans" aesthetic.

To achieve the friendly and modern vibe, the typeface should be configured with a focus on legibility and warmth. For larger display and headline roles, utilize the variable axes to slightly increase the weight and width for a more "rounded" and impactful presence. For body text, maintain a standard width to ensure high readability. Labels use a slightly tighter tracking and medium weight to distinguish them as functional UI markers.

## Layout & Spacing

The design system employs a **fluid 12-column grid** for desktop and tablet, collapsing to a **4-column grid** for mobile devices. 

The spacing rhythm is based on a **4px base unit**, with the most common increments being 8px and 16px. This creates a tight but breathable layout. 
- **Margins:** 16px on mobile to maximize content space; 24px on desktop for a more expansive feel.
- **Gutters:** 24px fixed gutters to maintain a clear vertical rhythm between columns.
- **Alignment:** All components should snap to the 4px grid. Use "Spacing MD (16px)" for standard padding within containers to ensure a consistent internal rhythm.

## Elevation & Depth

This design system primarily uses **Tonal Layers** to communicate depth, moving away from heavy, traditional shadows. Hierarchy is established by varying the color luminosity of the surface containers.

- **Level 0 (Surface):** The base background of the application.
- **Level 1 (Low Elevation):** Used for cards and secondary content. Created by overlaying a 5% opacity of the primary color over the surface.
- **Level 2+ (High Elevation):** Reserved for interactive elements like menus and dialogs. These layers use a 10-15% primary color overlay and a very soft, diffused ambient shadow (0px 4px 12px, 8% opacity) to suggest they are floating above the main interface.
- **Glassmorphism:** Can be used sparingly for persistent navigation bars or top headers, utilizing a subtle backdrop blur (12px) to maintain context of the content scrolling underneath.

## Shapes

To align with the expressive and friendly brand personality, this design system uses a **fully rounded (Pill-shaped)** shape language. 

Every interactive element—from buttons and chips to input fields—should feature extreme corner rounding. This softness removes the "sharpness" typical of digital interfaces, making the product feel more tactile and organic. Large containers like cards or dialogs should use the `rounded-xl` (3rem) setting to maintain a consistent visual flow with the smaller pill-shaped components.

## Components

### Buttons
Buttons are strictly pill-shaped. **Filled Buttons** use the Primary color with high-contrast text. **Tonal Buttons** use the Primary Container color (a muted version of primary) for a softer, secondary action.

### Chips & Inputs
Chips are small, fully rounded capsules used for filtering or tags. Input fields use a "Filled" style with a bottom-only border or a fully rounded "Outlined" style. The focus state should always trigger a transition in the border thickness and color to the primary role.

### Cards
Cards are the primary container for content. They should have a `rounded-lg` or `rounded-xl` corner radius. Use a subtle tonal fill (Elevation Level 1) rather than a border to define the card's boundaries.

### Floating Action Button (FAB)
The FAB is a signature element. While standard buttons are pill-shaped, the FAB can take a "Squircle" or even more exaggerated rounded-square shape (using `rounded-xl`) to stand out as the primary call to action on the screen.

### Checkboxes & Radios
Checkboxes should have a soft, 4px corner radius even within this rounded system to maintain their "square" identity, while Radio buttons remain perfectly circular. Both should use the primary color for their selected state.
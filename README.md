# Damage Pop (Fabric, Minecraft 26.3)

Al golpear a un mob aparece un numero flotante con el daño real que le hiciste:

- Blanco: golpe normal
- Amarillo grande + "¡CRÍTICO!": golpe critico (cayendo)
- Gris chico + "Meh...": golpe con menos de 1 corazon de daño
- Rojo grande + "¡KILL!!": el golpe que mata al mob

## Compilar sin instalar nada (GitHub Actions)

1. Crea un repositorio nuevo en github.com (cuenta gratis).
2. Sube TODO el contenido de esta carpeta (incluida la carpeta oculta `.github`).
   Si la web no te deja subir `.github`, usa "Add file > Create new file", escribe
   `.github/workflows/build.yml` como nombre y pega el contenido del archivo.
3. Abre la pestaña **Actions**, espera a que termine "build" (unos 3-5 min).
4. Entra a la ejecucion y baja **Artifacts**: dentro esta `damage-pop-1.0.0.jar`.
   (El `-sources.jar` no lo necesitas.)

## Instalar

En la carpeta `mods` de tu Fabric 26.3 pon:
- `damage-pop-1.0.0.jar`
- Fabric API para 26.3

Si juegas en un servidor, el mod va en el servidor (los demas jugadores no necesitan instalarlo).

## Ajustes

Arriba de `DamagePopMod.java` hay constantes: daño para "Meh", textos, altura, duracion.

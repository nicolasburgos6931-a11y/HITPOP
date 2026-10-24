package com.damagepop;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Damage Pop: muestra numeros flotantes con el daño que le haces a los mobs,
 * mas efectos de texto: ¡CRÍTICO!, Meh... y ¡KILL!!
 *
 * Los textos son entidades "text_display" de vanilla que se crean y se animan
 * con comandos, asi no hace falta ningun codigo de renderizado.
 */
public class DamagePopMod implements ModInitializer {

	// ------------------------------------------------------------------
	// AJUSTES (puedes cambiarlos y volver a compilar)
	// ------------------------------------------------------------------

	/** Golpes con menos daño que esto muestran "Meh..." (2.0 = 1 corazon). */
	private static final float MEH_DAMAGE = 2.0f;

	/** Cuantos bloques sube el texto mientras desaparece. */
	private static final double RISE_BLOCKS = 0.9;

	/** Textos de los efectos. */
	private static final String TEXT_CRIT = "¡CRÍTICO!";
	private static final String TEXT_MEH = "Meh...";
	private static final String TEXT_KILL = "¡KILL!!";

	// Duracion de la animacion en ticks (20 ticks = 1 segundo).
	private static final int RISE_TICKS = 12;
	private static final int FADE_AT = 13;
	private static final int FADE_TICKS = 6;
	private static final int LIFETIME = 20;

	// ------------------------------------------------------------------

	/** Un texto que todavia no se ha creado en el mundo. */
	private static final class Request {
		String dim;
		double x, y, z;
		String text;
		String color;
		boolean bold;
		double scale;
	}

	/** Un texto que ya esta en el mundo y se esta animando. */
	private static final class Popup {
		String dim;
		String tag;
		double scale;
		int age;
	}

	private static final List<Request> QUEUE = new ArrayList<>();
	private static final List<Popup> ACTIVE = new ArrayList<>();
	private static int counter = 0;

	// Vida del mob justo antes de recibir el golpe.
	private static LivingEntity healthEntity = null;
	private static float healthBefore = 0f;

	@Override
	public void onInitialize() {
		// Antes del daño: guardamos la vida para calcular el daño real.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			healthEntity = entity;
			healthBefore = entity.getHealth();
			return true; // no cancelamos nada
		});

		// Despues del daño (si el mob sigue vivo).
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (!(source.getEntity() instanceof ServerPlayer player) || entity == player) {
				return;
			}

			float dealt = (healthEntity == entity) ? (healthBefore - entity.getHealth()) : damageTaken;
			healthEntity = null;

			if (blocked || dealt < 0.05f) {
				return;
			}

			String number = String.format(Locale.ROOT, "%.1f", dealt);
			boolean melee = source.getDirectEntity() == player;

			if (melee && isCritical(player)) {
				pop(entity, number, "yellow", true, 1.4, 0);
				pop(entity, TEXT_CRIT, "gold", true, 1.0, 1);
			} else if (dealt < MEH_DAMAGE) {
				pop(entity, number, "gray", false, 0.8, 0);
				pop(entity, TEXT_MEH, "dark_gray", false, 0.7, 1);
			} else {
				pop(entity, number, "white", false, 1.0, 0);
			}
		});

		// Muerte causada por un jugador: ¡KILL!!
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(source.getEntity() instanceof ServerPlayer player) || entity == player) {
				return;
			}

			float dealt = (healthEntity == entity) ? healthBefore : 0f;
			healthEntity = null;

			if (dealt > 0.05f) {
				pop(entity, String.format(Locale.ROOT, "%.1f", dealt), "red", true, 1.6, 0);
				pop(entity, TEXT_KILL, "dark_red", true, 1.3, 1);
			} else {
				pop(entity, TEXT_KILL, "dark_red", true, 1.3, 0);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(DamagePopMod::onTick);

		// Limpieza: si cierras el mundo con textos en el aire, se borran.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Popup p : ACTIVE) {
				run(server, "execute in " + p.dim + " run kill " + selector(p.tag));
			}
			ACTIVE.clear();
			QUEUE.clear();
		});
	}

	/** Aproximacion del golpe critico de vanilla: cayendo, sin correr, fuera del agua. */
	private static boolean isCritical(ServerPlayer player) {
		return player.fallDistance > 0.0
				&& !player.onGround()
				&& !player.isInWater()
				&& !player.isSprinting()
				&& !player.isPassenger();
	}

	/** Pone un texto en la cola; se crea al final del tick. row 0 = el numero, row 1 = la etiqueta de encima. */
	private static void pop(LivingEntity target, String text, String color, boolean bold, double scale, int row) {
		if (!(target.level() instanceof ServerLevel level)) {
			return;
		}

		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		Request r = new Request();
		r.dim = level.dimension().identifier().toString();
		r.x = target.getX() + (rnd.nextDouble() - 0.5) * 0.7;
		r.y = target.getY() + target.getBbHeight() + 0.2 + row * 0.35 * Math.max(1.0, scale);
		r.z = target.getZ() + (rnd.nextDouble() - 0.5) * 0.7;
		r.text = text;
		r.color = color;
		r.bold = bold;
		r.scale = scale;
		QUEUE.add(r);
	}

	private static void onTick(MinecraftServer server) {
		// 1) Animar los textos que ya existen (antes de crear los nuevos,
		//    asi siempre pasa al menos 1 tick entre crear y animar).
		Iterator<Popup> it = ACTIVE.iterator();
		while (it.hasNext()) {
			Popup p = it.next();
			p.age++;

			if (p.age == 1) {
				// Crece y sube.
				run(server, merge(p, RISE_TICKS, p.scale, RISE_BLOCKS));
			} else if (p.age == FADE_AT) {
				// Se encoge hasta desaparecer mientras termina de subir.
				run(server, merge(p, FADE_TICKS, 0.05, RISE_BLOCKS + 0.3));
			} else if (p.age >= LIFETIME) {
				run(server, "execute in " + p.dim + " run kill " + selector(p.tag));
				it.remove();
			}
		}

		// 2) Crear los textos nuevos.
		if (!QUEUE.isEmpty()) {
			for (Request r : QUEUE) {
				spawn(server, r);
			}
			QUEUE.clear();
		}
	}

	private static void spawn(MinecraftServer server, Request r) {
		Popup p = new Popup();
		p.dim = r.dim;
		p.tag = "dpop" + (counter++);
		p.scale = r.scale;
		p.age = 0;

		double start = r.scale * 0.4; // empieza chico y "revienta" hacia su tamaño

		String cmd = "execute in " + r.dim + " run summon minecraft:text_display "
				+ num(r.x) + " " + num(r.y) + " " + num(r.z) + " {"
				+ "Tags:[\"damagepop\",\"" + p.tag + "\"],"
				+ "text:{text:\"" + r.text + "\",color:\"" + r.color + "\",bold:" + r.bold + "},"
				+ "billboard:\"center\","
				+ "background:0,"
				+ "shadow:true,"
				+ "see_through:false,"
				+ "brightness:{sky:15,block:15},"
				+ "transformation:" + transformation(start, 0.0)
				+ "}";

		run(server, cmd);
		ACTIVE.add(p);
	}

	private static String merge(Popup p, int duration, double scale, double rise) {
		return "execute in " + p.dim + " run data merge entity " + selector(p.tag)
				+ " {start_interpolation:0,interpolation_duration:" + duration
				+ ",transformation:" + transformation(scale, rise) + "}";
	}

	private static String transformation(double scale, double rise) {
		String s = flt(scale);
		return "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
				+ "scale:[" + s + "," + s + "," + s + "],"
				+ "translation:[0f," + flt(rise) + ",0f]}";
	}

	private static String selector(String tag) {
		return "@e[type=minecraft:text_display,tag=" + tag + ",limit=1]";
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	private static String flt(double v) {
		return String.format(Locale.ROOT, "%.3f", v) + "f";
	}

	/** Ejecuta un comando como el servidor, sin mensajes en el chat ni en la consola. */
	private static void run(MinecraftServer server, String command) {
		try {
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack().withSuppressedOutput(), command);
		} catch (Exception e) {
			// Nunca queremos que un fallo de un texto tire el servidor.
		}
	}
}

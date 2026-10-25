//! `botally` and `botenemy`: real bots, the ones that shoot and play the
//! objective, added to the host's team or to the other one.

use bevy::prelude::*;

use crate::{ConsoleCommand, ConsoleRegistry, ConsoleSettings, ConsoleState};

const MOST_AT_ONCE: u32 = 6;

pub(crate) fn register_botteam_commands(registry: &mut ConsoleRegistry) {
    for (name, usage) in [
        ("botally", "botally [N] — add N bots to your team (1 to 6)"),
        ("botenemy", "botenemy [N] — add N bots to the other team (1 to 6)"),
    ] {
        if registry.resolve(name).is_none() {
            registry.register(crate::CommandSpec::new(name).usage(usage));
        }
    }
}

pub(crate) fn route_botteam_commands(
    mut events: MessageReader<ConsoleCommand>,
    mut console: ResMut<ConsoleState>,
    settings: Res<ConsoleSettings>,
    mut bot_add: Option<ResMut<bots::BotAddQueue>>,
) {
    for command in events.read() {
        let (side, label) = match command.name.as_str() {
            "botally" => (bots::BotSide::Friendly, "ally"),
            "botenemy" => (bots::BotSide::Enemy, "enemy"),
            _ => continue,
        };
        let count = command
            .args
            .first()
            .and_then(|text| text.parse::<u32>().ok())
            .unwrap_or(1)
            .clamp(1, MOST_AT_ONCE);
        match bot_add.as_deref_mut() {
            Some(queue) => {
                queue.push_side(count, side);
                console.echo(
                    format!("{count} {label} bot(s) joining"),
                    settings.log_capacity,
                );
            }
            None => console.echo(
                "bots can only be added to a game you host",
                settings.log_capacity,
            ),
        }
    }
}
